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

@Service
public class StudentService {

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

        double courseworkLoad = courseworkLoad(student, sem);
        double focusLoad = WorkloadCalculator.activeWeight(sem.getFocusActivities());
        double recoveryLoad = WorkloadCalculator.activeWeight(sem.getRecoveryActivities());
        WorkloadCalculator.Score score = WorkloadCalculator.score(courseworkLoad, focusLoad, recoveryLoad, latestLog(sem));

        // Daily history point for the trend charts (stored separately, one per student per day)
        analyticsService.recordSnapshot(student, sem.getNumber(), score.workloadIndex(), score.workloadPercent(),
                score.balanceScore(), score.risk());

        stats.put("balanceScore", Math.round(score.balanceScore()));
        stats.put("workloadIndex", Math.round(score.workloadIndex() * 10.0) / 10.0);
        stats.put("workloadPercent", Math.round(score.workloadPercent() * 10.0) / 10.0);
        stats.put("burnoutRisk", score.risk());
        stats.put("sustainedOverload", analyticsService.hasSustainedOverload(username));
        stats.put("burnout", assess(student, courseworkLoad, recoveryLoad));
        stats.put("assignments", sem.getAssignments());
        stats.put("wellnessLogs", sem.getWellnessLogs());
        stats.put("focusActivities", sem.getFocusActivities());
        stats.put("recoveryActivities", sem.getRecoveryActivities());
        stats.put("subjectNames", sem.getSubjectNames());

        return stats;
    }

    // The most recent check-in this semester, or null
    private static WellnessLog latestLog(Semester sem) {
        List<WellnessLog> logs = sem.getWellnessLogs();
        return logs.isEmpty() ? null : logs.get(logs.size() - 1);
    }

    private double courseworkLoad(Student student, Semester sem) {
        return WorkloadCalculator.courseworkLoad(sem, name -> subjectRepository
                .findBySubjectNameIgnoreCaseAndUsernameAndSemesterNumber(name, student.getUsername(), sem.getNumber()));
    }

    // Burnout classes and intervention tier from the student's check-ins and latest snapshots
    private BurnoutClassifier.Assessment assess(Student student, double courseworkLoad, double recoveryLoad) {
        return BurnoutClassifier.assess(student.allWellnessLogs(), analyticsService.getRecentSnapshots(student.getUsername()),
                courseworkLoad, recoveryLoad, LocalDate.now());
    }

    // ================= Subjects (scoped to the active semester) =================

    public Subject addSubject(String username, Subject request) {
        Student s = requireStudent(username);
        Semester sem = requireActiveSemester(s);

        // A subject's department is optional, but must come from the list when given
        String department = request.getDepartment() == null || request.getDepartment().isBlank() ? null : request.getDepartment();
        if (department != null) requireKnownDepartment(department);

        String name = request.getSubjectName().trim();
        Subject existing = subjectRepository.findBySubjectNameIgnoreCaseAndUsernameAndSemesterNumber(
                name, username, sem.getNumber());
        if (existing != null) throw ApiException.conflict("That subject already exists this semester.");

        Subject subject = new Subject();
        subject.setUsername(username);
        subject.setSemesterNumber(sem.getNumber());
        subject.setSubjectName(name);
        subject.setDepartment(Departments.normalize(department));
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
        a.setDifficultyWeight(WorkloadCalculator.difficultyToWeight(a.getDifficulty()));
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

        // ---- Burnout classes and the tiered interventions they call for ----
        double courseworkLoad = sem == null ? 0 : courseworkLoad(student, sem);
        double focusLoad = sem == null ? 0 : WorkloadCalculator.activeWeight(sem.getFocusActivities());
        double recoveryLoad = sem == null ? 0 : WorkloadCalculator.activeWeight(sem.getRecoveryActivities());
        BurnoutClassifier.Assessment burnout = assess(student, courseworkLoad, recoveryLoad);
        result.put("burnout", burnout);
        result.put("interventions", interventions(student, sem, burnout));
        result.put("copingPlan", student.getCopingPlan() == null ? null
                : CopingPlans.progress(student.getCopingPlan(), student.allWellnessLogs(), LocalDate.now()));

        List<Map<String, Object>> recommendations = new ArrayList<>();
        if (sem == null) {
            recommendations.add(insightRec("INFO", "Start a semester from the Semester tab to unlock insights tailored to your current workload."));
            result.put("hasActiveSemester", false);
            result.put("recommendations", recommendations);
            return result;
        }
        result.put("hasActiveSemester", true);

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

        // At Tier 3 the routine tips stop, so only the serious warnings stay next to the escalation
        if (burnout.tier() == 3) {
            recommendations.removeIf(r -> !"HIGH".equals(r.get("severity")));
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

    private static Map<String, Object> intervention(int tier, String kind, String title, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("tier", tier);
        m.put("kind", kind);
        m.put("title", title);
        m.put("message", message);
        return m;
    }

    // What to do about each burnout class that fired:
    //   Tier 3 (CTRL)     — escalate to a counsellor, with the summary report to share
    //   Tier 2 (PHY, EMO) — offer a 4-week coping plan (unless one is already running)
    //   Tier 1 (COG, ORG) — lightweight nudges; left out at Tier 3 so they don't bury the escalation
    private List<Map<String, Object>> interventions(Student student, Semester sem, BurnoutClassifier.Assessment burnout) {
        Set<String> codes = new HashSet<>(burnout.codes());
        List<Map<String, Object>> list = new ArrayList<>();

        if (codes.contains("CTRL")) {
            list.add(intervention(3, "ESCALATION", "Please talk to a counsellor",
                    "Your workload has stayed above 85% of capacity for three days in a row. That's more than a busy week — "
                            + "please book a session with your campus counsellor or a mental health professional, and share your "
                            + "summary report so they can see your recent data."));
        }

        boolean planRunning = CopingPlans.isRunning(student.getCopingPlan(), LocalDate.now());
        if (!planRunning && codes.contains("PHY")) {
            Map<String, Object> offer = intervention(2, "PLAN_OFFER", CopingPlans.PlanType.SLEEP.title,
                    "Short nights or very low energy keep showing up in your check-ins. This 4-week plan rebuilds your sleep "
                            + "a step at a time, tracked from your daily check-ins.");
            offer.put("planType", CopingPlans.PlanType.SLEEP.name());
            list.add(offer);
        }
        if (!planRunning && codes.contains("EMO")) {
            Map<String, Object> offer = intervention(2, "PLAN_OFFER", CopingPlans.PlanType.STRESS.title,
                    "Stress has been your main mood this week. This 4-week plan builds recovery time back into your routine "
                            + "and tracks how you feel through your check-ins.");
            offer.put("planType", CopingPlans.PlanType.STRESS.name());
            list.add(offer);
        }

        if (burnout.tier() < 3) {
            if (codes.contains("COG")) {
                list.add(intervention(1, "NUDGE", "Split big tasks into focus blocks", focusBlockHint(sem)));
                list.add(intervention(1, "NUDGE", "Take a real break today",
                        "Spend 20 minutes away from screens — a short walk counts. It does more for your load than another hour of study."));
            }
            if (codes.contains("ORG")) {
                list.add(intervention(1, "NUDGE", "Plan a fixed daily study slot",
                        "Your workload swings sharply from day to day. A fixed daily slot works better than cramming right before deadlines."));
                list.add(intervention(1, "NUDGE", "Put every deadline in the calendar",
                        "Add your upcoming assignments now so nothing piles up unnoticed."));
            }
        }
        return list;
    }

    // Points the student at their most urgent pending assignment, if there is one
    private static String focusBlockHint(Semester sem) {
        String generic = "Work in 45-minute blocks with a 5-minute break between them, starting with whatever is due soonest.";
        if (sem == null) return generic;
        return sem.getAssignments().stream()
                .filter(a -> "Pending".equalsIgnoreCase(a.getStatus()) && a.getDeadline() != null && !a.getDeadline().isBlank())
                .min(Comparator.comparing(Student.Assignment::getDeadline))
                .map(a -> {
                    String deadline = a.getDeadline().replace('T', ' ');
                    boolean overdue = deadline.substring(0, Math.min(10, deadline.length())).compareTo(LocalDate.now().toString()) < 0;
                    return "Work in 45-minute blocks with a 5-minute break between them. Start with \"" + a.getTitle()
                            + "\" (" + a.getSubject() + "), " + (overdue ? "overdue since " : "due ") + deadline + ".";
                })
                .orElse(generic);
    }

    // ================= Coping plans (Tier 2) =================

    public CopingPlans.PlanProgress startCopingPlan(String username, String type) {
        Student s = requireStudent(username);
        CopingPlans.PlanType planType;
        try {
            planType = CopingPlans.PlanType.valueOf(String.valueOf(type));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown coping plan");
        }
        LocalDate today = LocalDate.now();
        if (CopingPlans.isRunning(s.getCopingPlan(), today)) {
            throw ApiException.conflict("You already have a coping plan running. End it before starting a new one.");
        }
        s.setCopingPlan(new CopingPlan(planType.name(), today.toString(), null));
        repository.save(s);
        return CopingPlans.progress(s.getCopingPlan(), s.allWellnessLogs(), today);
    }

    public void endCopingPlan(String username) {
        Student s = requireStudent(username);
        LocalDate today = LocalDate.now();
        if (!CopingPlans.isRunning(s.getCopingPlan(), today)) {
            throw ApiException.conflict("You don't have a coping plan running.");
        }
        s.getCopingPlan().setEndedDate(today.toString());
        repository.save(s);
    }

    // ================= Summary report (Tier 3: something to share with a counsellor) =================

    public Map<String, Object> getSummaryReport(String username) {
        Student student = requireStudent(username);
        Semester sem = activeSemester(student);
        LocalDate today = LocalDate.now();
        List<AnalyticsResult> recentNewestFirst = analyticsService.getRecentSnapshots(username);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedOn", today.toString());

        Map<String, Object> who = new LinkedHashMap<>();
        who.put("name", student.getName());
        who.put("username", student.getUsername());
        who.put("department", student.getDepartment());
        report.put("student", who);

        if (sem != null) {
            Map<String, Object> semInfo = new LinkedHashMap<>();
            semInfo.put("number", sem.getNumber());
            semInfo.put("startDate", sem.getStartDate());
            semInfo.put("endDate", sem.getEndDate());
            report.put("semester", semInfo);
        }

        if (!recentNewestFirst.isEmpty()) {
            AnalyticsResult latest = recentNewestFirst.get(0);
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("date", latest.getDate());
            current.put("balanceScore", Math.round(latest.getBalanceScore()));
            current.put("burnoutRisk", latest.getBurnoutRisk());
            current.put("workloadIndex", Math.round(latest.getWorkloadIndex() * 10.0) / 10.0);
            current.put("workloadPercent", Math.round(latest.getWorkloadPercent() * 10.0) / 10.0);
            current.put("sustainedOverload", AnalyticsService.isSustainedOverload(recentNewestFirst));
            report.put("current", current);
        }

        double coursework = sem == null ? 0 : courseworkLoad(student, sem);
        double recovery = sem == null ? 0 : WorkloadCalculator.activeWeight(sem.getRecoveryActivities());
        report.put("burnout", BurnoutClassifier.assess(student.allWellnessLogs(), recentNewestFirst, coursework, recovery, today));

        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = recentNewestFirst.size() - 1; i >= 0; i--) {
            AnalyticsResult r = recentNewestFirst.get(i);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", r.getDate());
            point.put("balanceScore", Math.round(r.getBalanceScore()));
            point.put("workloadPercent", Math.round(r.getWorkloadPercent()));
            point.put("burnoutRisk", r.getBurnoutRisk());
            history.add(point);
        }
        report.put("balanceHistory", history);

        List<WellnessLog> checkIns = student.allWellnessLogs().stream()
                .filter(l -> l.getDate() != null)
                .sorted(Comparator.comparing(WellnessLog::getDate).reversed())
                .limit(14)
                .toList();
        report.put("checkIns", checkIns);
        if (!checkIns.isEmpty()) {
            Map<String, Object> averages = new LinkedHashMap<>();
            averages.put("sleepHours", Math.round(checkIns.stream().mapToDouble(WellnessLog::getSleepHours).average().orElse(0) * 10.0) / 10.0);
            averages.put("studyHours", Math.round(checkIns.stream().mapToDouble(WellnessLog::getStudyHours).average().orElse(0) * 10.0) / 10.0);
            averages.put("stressedDays", checkIns.stream().filter(WorkloadCalculator::isStressed).count());
            report.put("averages", averages);
        }

        if (sem != null) {
            report.put("focusActivities", sem.getFocusActivities().stream().filter(Activity::isActive).toList());
            report.put("recoveryActivities", sem.getRecoveryActivities().stream().filter(Activity::isActive).toList());
            List<Student.Assignment> pending = sem.getAssignments().stream()
                    .filter(a -> "Pending".equalsIgnoreCase(a.getStatus()))
                    .sorted(Comparator.comparing(a -> String.valueOf(a.getDeadline())))
                    .toList();
            report.put("pendingAssignments", pending);
        }

        if (student.getCopingPlan() != null) {
            report.put("copingPlan", CopingPlans.progress(student.getCopingPlan(), student.allWellnessLogs(), today));
        }
        return report;
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

    // Any account (student, faculty or admin) by username — used to look up the logged-in user
    public Student getAccount(String username) {
        return requireStudent(username);
    }

    // Students and faculty must belong to one of the fixed departments
    private static void requireKnownDepartment(String department) {
        if (!Departments.isKnown(department)) throw ApiException.badRequest("Pick a department from the list");
    }

    // Public signup — always creates a STUDENT account
    public boolean registerStudent(AccountRequest request) {
        requireKnownDepartment(request.department());
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
        requireKnownDepartment(request.department());
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
