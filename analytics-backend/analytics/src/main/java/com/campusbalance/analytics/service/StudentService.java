package com.campusbalance.analytics.service;

import com.campusbalance.analytics.dto.AccountRequest;
import com.campusbalance.analytics.model.*;
import com.campusbalance.analytics.repository.StudentRepository;
import com.campusbalance.analytics.repository.SubjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StudentService {

    // "Max Capacity" baseline from the CampusBalance Score formula: 60 hrs of productive work/week
    private static final double MAX_CAPACITY_HOURS = 60.0;
    private static final int WELLNESS_LOG_XP = 20;
    private static final int ASSIGNMENT_XP = 50;
    static final String NO_ACTIVE_SEMESTER = "No active semester. Start one from the Semester tab first.";

    @Autowired
    private StudentRepository repository;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Student requireStudent(String username) {
        Student s = repository.findByUsername(username);
        if (s == null) throw ApiException.notFound("Student not found");
        return s;
    }

    // ================= Semester lifecycle =================

    private static Semester findActive(Student s) {
        return s.getSemesters().stream().filter(Semester::isActive).findFirst().orElse(null);
    }

    // Returns the live active semester, or null if there is none. If its end date has already
    // passed, it is archived and saved first. Only writes to the database when that archive
    // actually happens, so read-only requests don't rewrite the student document.
    private Semester activeSemester(Student s) {
        Semester active = findActive(s);
        if (active != null && active.hasEndedBy(LocalDate.now())) {
            archiveSemester(s, active);
            repository.save(s);
            return null;
        }
        return active;
    }

    private Semester requireActiveSemester(Student s) {
        Semester sem = activeSemester(s);
        if (sem == null) throw ApiException.conflict(NO_ACTIVE_SEMESTER);
        return sem;
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
        s.setActiveSemesterNumber(0);
    }

    // Body: { startDate, endDate } (yyyy-MM-dd)
    public Semester createSemester(String username, Semester request) {
        Student s = requireStudent(username);

        LocalDate start = parseDate(request.getStartDate());
        LocalDate end = parseDate(request.getEndDate());
        if (start == null || end == null) throw ApiException.badRequest("Pick a valid start and end date");
        if (!end.isAfter(start)) throw ApiException.badRequest("The end date must be after the start date");
        if (end.isBefore(LocalDate.now())) throw ApiException.badRequest("That end date has already passed");

        if (activeSemester(s) != null) {
            throw ApiException.conflict("You already have an active semester. End it before starting a new one.");
        }

        int nextNumber = s.getSemesters().stream().mapToInt(Semester::getNumber).max().orElse(0) + 1;
        Semester sem = new Semester();
        sem.setNumber(nextNumber);
        sem.setStartDate(start.toString());
        sem.setEndDate(end.toString());
        sem.setActive(true);
        s.getSemesters().add(sem);
        s.setActiveSemesterNumber(nextNumber);
        repository.save(s);
        return sem;
    }

    // Manual early end, triggered from the UI — same archive logic as the automatic date check
    public void endSemester(String username) {
        Student s = requireStudent(username);
        Semester active = findActive(s);
        if (active == null) throw ApiException.conflict("No active semester to end.");
        archiveSemester(s, active);
        repository.save(s);
    }

    // Full semester history (active + archived) for the Profile view
    public List<Semester> getAllSemesters(String username) {
        Student s = requireStudent(username);
        activeSemester(s); // lazily auto-archive if expired
        return s.getSemesters();
    }

    // ================= Dashboard =================

    public Map<String, Object> getDashboardStats(String username) {
        Student student = requireStudent(username);
        Semester sem = activeSemester(student);

        Map<String, Object> stats = new HashMap<>();
        stats.put("name", student.getName());
        stats.put("role", student.getRole());
        stats.put("department", student.getDepartment());
        stats.put("totalXP", student.getTotalXP());
        stats.put("dailyStreak", currentStreak(student));

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

        // 2. Focus Activities — each active one adds stress
        double focusLoad = activeWeight(sem.getFocusActivities());

        // 3. Recovery Activities — each active one relieves stress, subtracted straight out of the load
        double recoveryLoad = activeWeight(sem.getRecoveryActivities());

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

        // Daily history point for the trend charts (stored separately, one per student per day)
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

    private static double activeWeight(List<Activity> activities) {
        return activities.stream().filter(Activity::isActive).mapToDouble(Activity::getWeight).sum();
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

    public Subject addSubject(String username, Subject request) {
        Student s = requireStudent(username);
        Semester sem = requireActiveSemester(s);

        String name = request.getSubjectName().trim();
        Subject existing = subjectRepository.findBySubjectNameIgnoreCaseAndUsernameAndSemesterNumber(
                name, username, sem.getNumber());
        if (existing != null) throw ApiException.conflict("That subject already exists this semester.");

        Subject subject = new Subject();
        subject.setUsername(username);
        subject.setSemesterNumber(sem.getNumber());
        subject.setSubjectName(name);
        subject.setDepartment(Departments.normalize(request.getDepartment()));
        subject.setCredits(request.getCredits());
        subject.setConstant(false);
        subject.setCalibrationComplete(false);
        subject.setCalibrationStartDate(LocalDate.now().toString());
        subjectRepository.save(subject);

        sem.getSubjectNames().add(subject.getSubjectName());
        repository.save(s);
        return subject;
    }

    public List<Subject> getSubjects(String username) {
        Student s = requireStudent(username);
        Semester sem = activeSemester(s);
        if (sem == null) return Collections.emptyList();
        return subjectRepository.findByUsernameAndSemesterNumber(username, sem.getNumber());
    }

    // ================= Focus / Recovery Activities =================

    public void addFocusActivity(String username, Activity activity) {
        addActivity(username, activity, Semester::getFocusActivities);
    }

    public void addRecoveryActivity(String username, Activity activity) {
        addActivity(username, activity, Semester::getRecoveryActivities);
    }

    public void toggleFocusActivity(String username, String name) {
        toggleActivity(username, name, Semester::getFocusActivities);
    }

    public void toggleRecoveryActivity(String username, String name) {
        toggleActivity(username, name, Semester::getRecoveryActivities);
    }

    // Names are unique per list (ignoring case) because pause/resume looks activities up by name
    private void addActivity(String username, Activity activity, Function<Semester, List<Activity>> listOf) {
        Student s = requireStudent(username);
        List<Activity> list = listOf.apply(requireActiveSemester(s));

        String name = activity.getName().trim();
        if (list.stream().anyMatch(a -> name.equalsIgnoreCase(a.getName()))) {
            throw ApiException.conflict("You already have \"" + name + "\" — pause or resume it instead.");
        }
        activity.setName(name);
        activity.setActive(true);
        list.add(activity);
        repository.save(s);
    }

    // Applies to every entry with that name, so older data that still has duplicates stays in sync
    private void toggleActivity(String username, String name, Function<Semester, List<Activity>> listOf) {
        Student s = requireStudent(username);
        List<Activity> matches = listOf.apply(requireActiveSemester(s)).stream()
                .filter(a -> name.equalsIgnoreCase(a.getName()))
                .toList();
        if (matches.isEmpty()) throw ApiException.notFound("Activity not found");

        boolean nowActive = !matches.get(0).isActive();
        matches.forEach(a -> a.setActive(nowActive));
        repository.save(s);
    }

    // ================= Wellness logs (scoped to the active semester) =================

    public void addWellnessLog(String username, WellnessLog log) {
        Student s = requireStudent(username);
        Semester sem = requireActiveSemester(s);

        LocalDate today = LocalDate.now();
        boolean alreadyLoggedToday = sem.getWellnessLogs().stream()
                .anyMatch(l -> today.toString().equals(l.getDate()));
        if (alreadyLoggedToday) throw ApiException.conflict("You've already checked in today — come back tomorrow.");

        log.setDate(today.toString()); // server sets the date — don't trust the client's clock
        sem.getWellnessLogs().add(log);
        s.setTotalXP(s.getTotalXP() + WELLNESS_LOG_XP);
        updateStreak(s, today);
        repository.save(s);
    }

    // Continues the streak if the last check-in was yesterday, otherwise restarts it at 1
    private void updateStreak(Student s, LocalDate today) {
        LocalDate last = parseDate(s.getLastCheckInDate());
        if (today.equals(last)) return; // already counted today (e.g. a new semester started the same day)
        s.setDailyStreak(today.minusDays(1).equals(last) ? s.getDailyStreak() + 1 : 1);
        s.setLastCheckInDate(today.toString());
    }

    // The streak to show right now: it has lapsed if neither today nor yesterday had a check-in
    private int currentStreak(Student s) {
        LocalDate last = parseDate(s.getLastCheckInDate());
        if (last == null || last.isBefore(LocalDate.now().minusDays(1))) return 0;
        return s.getDailyStreak();
    }

    // ================= Assignments (scoped to the active semester) =================

    public Student.Assignment addAssignment(String username, Student.Assignment a) {
        Student s = requireStudent(username);
        Semester sem = requireActiveSemester(s);

        if (sem.getSubjectNames().stream().noneMatch(n -> n.equalsIgnoreCase(a.getSubject()))) {
            throw ApiException.badRequest("Add \"" + a.getSubject() + "\" on the Subjects tab first");
        }

        a.setId(UUID.randomUUID().toString());
        a.setTitle(a.getTitle().trim());
        a.setDifficultyWeight(difficultyToWeight(a.getDifficulty()));
        a.setStatus("Pending");
        a.setCompletedAt(null);
        sem.getAssignments().add(a);
        repository.save(s);
        return a;
    }

    public void submitAssignment(String username, String assignmentId) {
        Student s = requireStudent(username);
        Semester sem = requireActiveSemester(s);

        Student.Assignment a = sem.getAssignments().stream()
                .filter(x -> assignmentId.equals(x.getId()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Assignment not found"));
        // XP is awarded once per assignment
        if ("Completed".equalsIgnoreCase(a.getStatus())) throw ApiException.conflict("That assignment is already submitted.");

        a.setStatus("Completed");
        a.setCompletedAt(LocalDate.now().toString());
        s.setTotalXP(s.getTotalXP() + ASSIGNMENT_XP);
        repository.save(s);
    }

    // ================= Insights (trend forecast + rule-based recommendations) =================

    public Map<String, Object> getInsights(String username) {
        Student student = requireStudent(username);
        Semester sem = activeSemester(student);
        Map<String, Object> result = new HashMap<>();

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
        double focusLoad = activeWeight(sem.getFocusActivities());
        double recoveryLoad = activeWeight(sem.getRecoveryActivities());

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

    // Returns the account if the credentials match, otherwise null
    public Student login(String username, String password) {
        if (username == null || password == null) return null;
        Student s = repository.findByUsername(username);
        if (s != null && s.getPassword() != null && passwordEncoder.matches(password, s.getPassword())) {
            return s;
        }
        return null;
    }

    // Any logged-in account (student, faculty or admin) can change its own password
    public void changePassword(String username, String currentPassword, String newPassword) {
        Student account = requireStudent(username);
        if (account.getPassword() == null || !passwordEncoder.matches(currentPassword, account.getPassword())) {
            throw ApiException.badRequest("Your current password is incorrect");
        }
        if (currentPassword.equals(newPassword)) {
            throw ApiException.badRequest("The new password must be different from the current one");
        }
        account.setPassword(passwordEncoder.encode(newPassword));
        repository.save(account);
    }

    // Public signup — always creates a STUDENT account
    public boolean registerStudent(AccountRequest request) {
        return createAccount(request, "STUDENT");
    }

    public boolean adminExists() {
        return !repository.findByRole("ADMIN").isEmpty();
    }

    // One-time bootstrap: only succeeds if no admin account exists yet anywhere in the system
    public boolean registerFirstAdmin(AccountRequest request) {
        if (adminExists()) return false;
        return createAccount(request, "ADMIN");
    }

    // Called from the admin dashboard to provision a faculty account
    public boolean createFaculty(AccountRequest request) {
        return createAccount(request, "FACULTY");
    }

    // Builds a fresh account from the whitelisted request fields only. Returns false if the username is taken.
    private boolean createAccount(AccountRequest request, String role) {
        if (repository.findByUsername(request.username()) != null) return false;

        Student account = new Student();
        account.setUsername(request.username());
        account.setPassword(passwordEncoder.encode(request.password()));
        account.setName(request.name());
        account.setDepartment(Departments.normalize(request.department()));
        account.setBatchYear(request.batchYear());
        account.setRole(role);
        repository.save(account);
        return true;
    }

    // ================= Helpers =================

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
