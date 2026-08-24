package com.campusbalance.analytics.service;

import com.campusbalance.analytics.model.*;
import com.campusbalance.analytics.repository.StudentRepository;
import com.campusbalance.analytics.repository.SubjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class StudentService {

    // "Max Capacity" baseline from the CampusBalance Score formula: 60 hrs of productive work/week
    private static final double MAX_CAPACITY_HOURS = 60.0;

    @Autowired
    private StudentRepository repository;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private AnalyticsService analyticsService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // ================= Semester lifecycle =================

    // Returns the live active semester, auto-archiving it first if its end date has already
    // passed. Returns null if there's no active semester (never created one, or it just archived).
    // Mutates `s` in memory only — callers are responsible for calling repository.save(s) afterward
    // so an auto-archive that happens here is never silently lost.
    private Semester getActiveSemester(Student s) {
        Semester active = s.getSemesters().stream()
                .filter(Semester::isActive)
                .findFirst()
                .orElse(null);
        if (active == null) return null;

        if (active.getEndDate() != null && !active.getEndDate().isBlank()) {
            LocalDate end = LocalDate.parse(active.getEndDate());
            if (LocalDate.now().isAfter(end)) {
                archiveSemester(s, active);
                return null;
            }
        }
        return active;
    }

    private void archiveSemester(Student s, Semester sem) {
        List<AnalyticsResult> history = analyticsService.getHistoryFor(s.getUsername());
        double avg = history.stream()
                .filter(r -> r.getCurrentSemester() == sem.getNumber())
                .mapToDouble(AnalyticsResult::getBalanceScore)
                .average().orElse(0);
        sem.setFinalBalanceScore(Math.round(avg * 10.0) / 10.0);
        sem.setArchivedDate(LocalDate.now().toString());
        sem.setActive(false);
    }

    public Map<String, Object> createSemester(String username, Semester request) {
        Student s = repository.findByUsername(username);
        Map<String, Object> result = new HashMap<>();
        if (s == null) { result.put("error", "Student not found"); return result; }

        // Auto-archive an expired active semester first, so the check below is accurate
        getActiveSemester(s);

        boolean hasLiveActive = s.getSemesters().stream().anyMatch(Semester::isActive);
        if (hasLiveActive) {
            repository.save(s);
            result.put("error", "You already have an active semester. End it before starting a new one.");
            return result;
        }

        int nextNumber = s.getSemesters().stream().mapToInt(Semester::getNumber).max().orElse(0) + 1;
        Semester sem = new Semester();
        sem.setNumber(nextNumber);
        sem.setStartDate(request.getStartDate());
        sem.setEndDate(request.getEndDate());
        sem.setActive(true);
        s.getSemesters().add(sem);
        s.setActiveSemesterNumber(nextNumber);
        repository.save(s);

        result.put("semester", sem);
        return result;
    }

    // Manual early end, triggered from the UI — same archive logic as the automatic date check
    public boolean endSemester(String username) {
        Student s = repository.findByUsername(username);
        if (s == null) return false;
        Semester active = s.getSemesters().stream().filter(Semester::isActive).findFirst().orElse(null);
        if (active == null) return false;
        archiveSemester(s, active);
        repository.save(s);
        return true;
    }

    // Full semester history (active + archived) for the Profile view
    public List<Semester> getAllSemesters(String username) {
        Student s = repository.findByUsername(username);
        if (s == null) return Collections.emptyList();
        getActiveSemester(s); // lazily auto-archive if expired
        repository.save(s);
        return s.getSemesters();
    }

    // ================= Dashboard =================

    public Map<String, Object> getDashboardStats(String username) {
        Student student = repository.findByUsername(username);
        Map<String, Object> stats = new HashMap<>();
        if (student == null) return stats;

        Semester sem = getActiveSemester(student);
        repository.save(student); // persist any auto-archive that just happened

        stats.put("name", student.getName());
        stats.put("role", student.getRole());
        stats.put("department", student.getDepartment());
        stats.put("totalXP", student.getTotalXP());
        stats.put("dailyStreak", student.getDailyStreak());

        if (sem == null) {
            stats.put("hasActiveSemester", false);
            return stats;
        }
        stats.put("hasActiveSemester", true);
        stats.put("semesterNumber", sem.getNumber());
        stats.put("semesterStart", sem.getStartDate());
        stats.put("semesterEnd", sem.getEndDate());

        // 1. Coursework load — uses each subject's calibrated Difficulty Coefficient (D_s)
        //    once its 2-week calibration phase is complete; falls back to the difficulty-string
        //    heuristic for subjects that haven't been calibrated yet.
        double courseworkLoad = computeCourseworkLoad(student, sem);

        // 2. Focus Activities (replaces the old fixed "Toggle DSA" switch) — each active one adds stress
        double focusLoad = sem.getFocusActivities().stream()
                .filter(Activity::isActive)
                .mapToDouble(Activity::getWeight)
                .sum();

        // 3. Recovery Activities — each active one relieves stress, subtracted straight out of the load
        double recoveryLoad = sem.getRecoveryActivities().stream()
                .filter(Activity::isActive)
                .mapToDouble(Activity::getWeight)
                .sum();

        // 4. Mood coefficient (Mc) from the latest wellness log this semester
        double moodCoef = 1.0;
        WellnessLog latestLog = null;
        if (!sem.getWellnessLogs().isEmpty()) {
            latestLog = sem.getWellnessLogs().get(sem.getWellnessLogs().size() - 1);
            String mood = latestLog.getMood() == null ? "" : latestLog.getMood();
            if (mood.contains("Stressed") || mood.contains("😫")) moodCoef = 1.5;
            else if (mood.contains("Neutral") || mood.contains("😐")) moodCoef = 1.2;
            else if (mood.contains("Good") || mood.contains("😄")) moodCoef = 0.8;
        }

        // 5. Total Workload Index (Wi) = (Coursework + Focus Activities - Recovery Activities) x Mood Coefficient
        double rawLoad = Math.max(0, courseworkLoad + focusLoad - recoveryLoad);
        double finalWorkloadIndex = rawLoad * moodCoef;
        double workloadPercent = (finalWorkloadIndex / MAX_CAPACITY_HOURS) * 100.0;

        // 6. CampusBalance Score (B) = (1 - Wi/MaxCapacity) x 100% + Recovery Bonus (sleep/energy)
        double recoveryBonus = 0;
        if (latestLog != null) {
            if (latestLog.getSleepHours() >= 7) recoveryBonus += 10;
            if (latestLog.getSleepHours() < 5) recoveryBonus -= 15;
            if (latestLog.getEnergyLevel() >= 4) recoveryBonus += 5;
        }
        double balanceScore = Math.min(100, Math.max(0, 100 - workloadPercent + recoveryBonus));
        String risk = (balanceScore < 40) ? "HIGH" : (balanceScore < 70 ? "MEDIUM" : "LOW");

        analyticsService.recordSnapshot(student, sem.getNumber(), finalWorkloadIndex, workloadPercent, balanceScore, risk);

        stats.put("balanceScore", Math.round(balanceScore));
        stats.put("workloadIndex", Math.round(finalWorkloadIndex * 10.0) / 10.0);
        stats.put("workloadPercent", Math.round(workloadPercent * 10.0) / 10.0);
        stats.put("burnoutRisk", risk);
        stats.put("sustainedOverload", analyticsService.hasSustainedOverload(username));
        stats.put("assignments", sem.getAssignments());
        stats.put("wellnessLogs", sem.getWellnessLogs());
        stats.put("focusActivities", sem.getFocusActivities());
        stats.put("recoveryActivities", sem.getRecoveryActivities());
        stats.put("subjectNames", sem.getSubjectNames());

        return stats;
    }

    private double computeCourseworkLoad(Student student, Semester sem) {
        double total = 0;
        Map<String, List<Student.Assignment>> bySubject = sem.getAssignments().stream()
                .filter(a -> "Pending".equalsIgnoreCase(a.getStatus()))
                .collect(Collectors.groupingBy(a -> a.getSubject() == null || a.getSubject().isBlank() ? "General" : a.getSubject()));

        for (Map.Entry<String, List<Student.Assignment>> entry : bySubject.entrySet()) {
            Subject subject = subjectRepository.findBySubjectNameIgnoreCaseAndUsernameAndSemesterNumber(
                    entry.getKey(), student.getUsername(), sem.getNumber());
            List<Student.Assignment> tasks = entry.getValue();

            if (subject != null && subject.isCalibrationComplete() && subject.getDifficultyCoefficient() != null) {
                // Wi contribution = D_s x H_i (approx. 3 hrs of combined assignment + lecture load per pending item)
                double hoursForSubject = tasks.size() * 3.0;
                total += subject.getDifficultyCoefficient() * hoursForSubject;
            } else {
                for (Student.Assignment a : tasks) {
                    int weight = a.getDifficultyWeight() > 0 ? a.getDifficultyWeight() : difficultyToWeight(a.getDifficulty());
                    total += weight * 3.0;
                }
            }
        }
        return total;
    }

    private int difficultyToWeight(String difficulty) {
        if (difficulty == null) return 1;
        if (difficulty.equalsIgnoreCase("Hard")) return 5;
        if (difficulty.equalsIgnoreCase("Medium")) return 3;
        return 1;
    }

    // ================= Subjects (scoped to the active semester) =================

    public Map<String, Object> addSubject(String username, Subject request) {
        Student s = repository.findByUsername(username);
        Map<String, Object> result = new HashMap<>();
        if (s == null) { result.put("error", "Student not found"); return result; }

        Semester sem = getActiveSemester(s);
        if (sem == null) {
            repository.save(s);
            result.put("error", "No active semester. Start one from the Semester tab first.");
            return result;
        }

        if (request.getSubjectName() == null || request.getSubjectName().isBlank()) {
            repository.save(s);
            result.put("error", "Subject name is required");
            return result;
        }

        Subject existing = subjectRepository.findBySubjectNameIgnoreCaseAndUsernameAndSemesterNumber(
                request.getSubjectName(), username, sem.getNumber());
        if (existing != null) {
            repository.save(s);
            result.put("error", "That subject already exists this semester.");
            return result;
        }

        Subject subject = new Subject();
        subject.setUsername(username);
        subject.setSemesterNumber(sem.getNumber());
        subject.setSubjectName(request.getSubjectName());
        subject.setDepartment(request.getDepartment());
        subject.setCredits(Math.max(request.getCredits(), 1));
        subject.setConstant(false);
        subject.setCalibrationComplete(false);
        subject.setCalibrationStartDate(LocalDate.now().toString());
        subjectRepository.save(subject);

        sem.getSubjectNames().add(subject.getSubjectName());
        repository.save(s);

        result.put("subject", subject);
        return result;
    }

    public List<Subject> getSubjects(String username) {
        Student s = repository.findByUsername(username);
        if (s == null) return Collections.emptyList();
        Semester sem = getActiveSemester(s);
        repository.save(s);
        if (sem == null) return Collections.emptyList();
        return subjectRepository.findByUsernameAndSemesterNumber(username, sem.getNumber());
    }

    // ================= Focus / Recovery Activities =================

    public boolean addFocusActivity(String username, Activity activity) {
        Student s = repository.findByUsername(username);
        if (s == null) return false;
        Semester sem = getActiveSemester(s);
        if (sem == null) { repository.save(s); return false; }
        activity.setActive(true);
        sem.getFocusActivities().add(activity);
        repository.save(s);
        return true;
    }

    public boolean addRecoveryActivity(String username, Activity activity) {
        Student s = repository.findByUsername(username);
        if (s == null) return false;
        Semester sem = getActiveSemester(s);
        if (sem == null) { repository.save(s); return false; }
        activity.setActive(true);
        sem.getRecoveryActivities().add(activity);
        repository.save(s);
        return true;
    }

    public void toggleFocusActivity(String username, String name) {
        Student s = repository.findByUsername(username);
        if (s == null) return;
        Semester sem = getActiveSemester(s);
        if (sem == null) { repository.save(s); return; }
        sem.getFocusActivities().stream()
                .filter(a -> a.getName().equalsIgnoreCase(name))
                .findFirst()
                .ifPresent(a -> a.setActive(!a.isActive()));
        repository.save(s);
    }

    public void toggleRecoveryActivity(String username, String name) {
        Student s = repository.findByUsername(username);
        if (s == null) return;
        Semester sem = getActiveSemester(s);
        if (sem == null) { repository.save(s); return; }
        sem.getRecoveryActivities().stream()
                .filter(a -> a.getName().equalsIgnoreCase(name))
                .findFirst()
                .ifPresent(a -> a.setActive(!a.isActive()));
        repository.save(s);
    }

    // ================= Wellness logs (scoped to the active semester) =================

    // Returns true if the log was saved, false if the student already logged today
    // or has no active semester.
    public boolean addWellnessLog(String username, WellnessLog log) {
        Student s = repository.findByUsername(username);
        if (s == null) return false;
        Semester sem = getActiveSemester(s);
        if (sem == null) { repository.save(s); return false; }

        String today = LocalDate.now().toString();
        boolean alreadyLoggedToday = sem.getWellnessLogs().stream()
                .anyMatch(l -> today.equals(l.getDate()));
        if (alreadyLoggedToday) {
            repository.save(s);
            return false;
        }

        log.setDate(today); // server sets the date — don't trust the client's clock
        sem.getWellnessLogs().add(log);
        s.setTotalXP(s.getTotalXP() + 20);
        repository.save(s);
        return true;
    }

    public Student login(String username, String password) {
        Student s = repository.findByUsername(username);
        if (s != null && s.getPassword() != null && passwordEncoder.matches(password, s.getPassword())) {
            s.setPassword(null); // never send the password hash back to the client
            return s;
        }
        return null; // Unauthorized
    }

    // ================= Assignments (scoped to the active semester) =================

    public boolean addAssignment(String username, Student.Assignment a) {
        Student s = repository.findByUsername(username);
        if (s == null) return false;
        Semester sem = getActiveSemester(s);
        if (sem == null) { repository.save(s); return false; }

        int weight = difficultyToWeight(a.getDifficulty());
        a.setDifficultyWeight(weight);
        a.setStatus("Pending");
        sem.getAssignments().add(a);
        repository.save(s);
        return true;
    }

    public void submitAssignment(String username, String title) {
        Student s = repository.findByUsername(username);
        if (s == null) return;
        Semester sem = getActiveSemester(s);
        if (sem == null) { repository.save(s); return; }
        sem.getAssignments().stream()
                .filter(a -> a.getTitle().equals(title))
                .findFirst()
                .ifPresent(a -> {
                    a.setStatus("Completed");
                    a.setCompletedAt(LocalDate.now().toString());
                    s.setTotalXP(s.getTotalXP() + 50); // XP Reward for submission
                });
        repository.save(s);
    }

        // ================= Insights (trend forecast + rule-based recommendations) =================

    public Map<String, Object> getInsights(String username) {
        Student student = repository.findByUsername(username);
        Map<String, Object> result = new HashMap<>();
        if (student == null) return result;

        Semester sem = getActiveSemester(student);
        repository.save(student);

        List<AnalyticsResult> history = analyticsService.getHistoryFor(username);
        List<AnalyticsResult> recent = history.size() > 14
                ? history.subList(history.size() - 14, history.size())
                : history;

        List<Map<String, Object>> trend = new ArrayList<>();
        for (AnalyticsResult r : recent) {
            Map<String, Object> point = new HashMap<>();
            point.put("date", r.getDate());
            point.put("balanceScore", r.getBalanceScore());
            point.put("workloadPercent", r.getWorkloadPercent());
            point.put("burnoutRisk", r.getBurnoutRisk());
            trend.add(point);
        }
        result.put("trend", trend);

        // ---- Forecast: simple linear regression of Balance Score over the recent history ----
        Map<String, Object> forecast = new HashMap<>();
        if (recent.size() >= 3) {
            int n = recent.size();
            double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
            for (int i = 0; i < n; i++) {
                double x = i;
                double y = recent.get(i).getBalanceScore();
                sumX += x; sumY += y; sumXY += x * y; sumXX += x * x;
            }
            double denom = (n * sumXX - sumX * sumX);
            double slope = denom == 0 ? 0 : (n * sumXY - sumX * sumY) / denom;
            double intercept = (sumY - slope * sumX) / n;

            List<Map<String, Object>> projected = new ArrayList<>();
            for (int d = 1; d <= 7; d++) {
                double projectedScore = Math.max(0, Math.min(100, intercept + slope * (n - 1 + d)));
                Map<String, Object> p = new HashMap<>();
                p.put("dayOffset", d);
                p.put("projectedBalanceScore", Math.round(projectedScore * 10.0) / 10.0);
                projected.add(p);
            }
            forecast.put("slopePerDay", Math.round(slope * 100.0) / 100.0);
            forecast.put("trend", slope < -0.5 ? "DECLINING" : slope > 0.5 ? "IMPROVING" : "STABLE");
            forecast.put("projected", projected);

            double lastScore = recent.get(n - 1).getBalanceScore();
            if (slope < 0 && lastScore >= 40) {
                double daysUntilHigh = (40 - lastScore) / slope;
                if (daysUntilHigh > 0 && daysUntilHigh <= 30) {
                    forecast.put("daysUntilHighRisk", (int) Math.ceil(daysUntilHigh));
                }
            }
        } else {
            forecast.put("trend", "INSUFFICIENT_DATA");
        }
        result.put("forecast", forecast);

        List<Map<String, Object>> recommendations = new ArrayList<>();
        if (sem == null) {
            recommendations.add(insightRec("INFO", "Start a semester from the Semester tab to unlock insights tailored to your current workload."));
            result.put("hasActiveSemester", false);
            result.put("recommendations", recommendations);
            return result;
        }
        result.put("hasActiveSemester", true);

        double courseworkLoad = computeCourseworkLoad(student, sem);
        double focusLoad = sem.getFocusActivities().stream().filter(Activity::isActive).mapToDouble(Activity::getWeight).sum();
        double recoveryLoad = sem.getRecoveryActivities().stream().filter(Activity::isActive).mapToDouble(Activity::getWeight).sum();

        Map<String, Object> breakdown = new HashMap<>();
        breakdown.put("courseworkLoad", Math.round(courseworkLoad * 10.0) / 10.0);
        breakdown.put("focusLoad", focusLoad);
        breakdown.put("recoveryLoad", recoveryLoad);
        result.put("breakdown", breakdown);

        double latestBalance = recent.isEmpty() ? 100 : recent.get(recent.size() - 1).getBalanceScore();
        double latestWorkloadPct = recent.isEmpty() ? 0 : recent.get(recent.size() - 1).getWorkloadPercent();
        result.put("latestBalanceScore", latestBalance);
        result.put("latestWorkloadPercent", latestWorkloadPct);

        if (analyticsService.hasSustainedOverload(username)) {
            recommendations.add(insightRec("HIGH", "Your workload has stayed above 85% of capacity for 3+ days straight. Consider talking to your advisor and pausing a Focus Activity."));
        }

        if (focusLoad > 0 && recoveryLoad == 0 && latestWorkloadPct > 60) {
            recommendations.add(insightRec("MEDIUM", "You have Focus Activities pushing your load up but no active Recovery Activities. Add one — even a light one helps offset the stress."));
        }

        if (focusLoad > 0 && focusLoad > courseworkLoad) {
            recommendations.add(insightRec("MEDIUM", "Focus Activities are contributing more load right now than your actual coursework. Consider pausing your lowest-priority one for a while."));
        }

        if (!sem.getWellnessLogs().isEmpty()) {
            List<WellnessLog> logs = sem.getWellnessLogs();
            List<WellnessLog> lastFewLogs = logs.size() > 3 ? logs.subList(logs.size() - 3, logs.size()) : logs;
            double avgSleep = lastFewLogs.stream().mapToDouble(WellnessLog::getSleepHours).average().orElse(8);
            if (avgSleep < 6) {
                recommendations.add(insightRec("HIGH", "Your average sleep over your last few logs is under 6 hours. Prioritize rest tonight — sleep debt compounds fast."));
            }
        } else {
            recommendations.add(insightRec("LOW", "Log a wellness check-in to get sleep/energy-aware insights."));
        }

        String trendDirection = (String) forecast.get("trend");
        if ("DECLINING".equals(trendDirection)) {
            Object daysUntilHigh = forecast.get("daysUntilHighRisk");
            if (daysUntilHigh != null) {
                recommendations.add(insightRec("HIGH", "Your Balance Score has been trending down. At this rate you could hit HIGH burnout risk in about " + daysUntilHigh + " day(s) — this is a good time to scale back."));
            } else {
                recommendations.add(insightRec("MEDIUM", "Your Balance Score has been trending down over your recent logs. Keep an eye on it."));
            }
        } else if ("IMPROVING".equals(trendDirection)) {
            recommendations.add(insightRec("POSITIVE", "Your Balance Score is trending up — whatever you're doing is working. Keep it going."));
        }

        if (recommendations.isEmpty()) {
            recommendations.add(insightRec("POSITIVE", "Everything looks balanced right now. No red flags in your current workload or recovery mix."));
        }

        result.put("recommendations", recommendations);
        return result;
    }

    private Map<String, Object> insightRec(String severity, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("severity", severity);
        m.put("message", message);
        return m;
    }

    // ================= Auth / Registration =================

    // Public signup — always creates a STUDENT account, regardless of what the client sends
    public boolean registerStudent(Student s) {
        if (repository.findByUsername(s.getUsername()) != null) return false;

        s.setPassword(passwordEncoder.encode(s.getPassword()));
        s.setRole("STUDENT");
        s.setSemesters(new ArrayList<>());
        s.setActiveSemesterNumber(0);
        repository.save(s);
        return true;
    }

    public boolean adminExists() {
        return !repository.findByRole("ADMIN").isEmpty();
    }

    // One-time bootstrap: only succeeds if no admin account exists yet anywhere in the system
    public boolean registerFirstAdmin(Student s) {
        if (adminExists()) return false;
        if (repository.findByUsername(s.getUsername()) != null) return false;

        s.setPassword(passwordEncoder.encode(s.getPassword()));
        s.setRole("ADMIN");
        repository.save(s);
        return true;
    }

    // Called from the admin dashboard to provision a faculty account
    public boolean createFaculty(Student s) {
        if (repository.findByUsername(s.getUsername()) != null) return false;

        s.setPassword(passwordEncoder.encode(s.getPassword()));
        s.setRole("FACULTY");
        repository.save(s);
        return true;
    }
}