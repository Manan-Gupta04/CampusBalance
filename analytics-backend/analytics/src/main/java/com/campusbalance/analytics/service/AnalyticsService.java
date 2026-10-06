package com.campusbalance.analytics.service;

import com.campusbalance.analytics.dto.CalibrationRequest;
import com.campusbalance.analytics.model.*;
import com.campusbalance.analytics.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Cross-student analytics: the 2-week Calibration Phase (Difficulty Coefficient D_s),
 * historical Workload/Balance snapshots, and the faculty/advisor/admin aggregate views.
 */
@Service
public class AnalyticsService {

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private CalibrationLogRepository calibrationLogRepository;

    @Autowired
    private AnalyticsResultRepository analyticsResultRepository;

    @Autowired
    private StudentRepository studentRepository;

    // ---------- Calibration Phase ----------

    // The subject should already exist in the student's active semester (added via the Subjects
    // tab); if it somehow doesn't, we create a minimal placeholder so the calibration log isn't lost.
    public Subject submitCalibrationLog(String username, CalibrationRequest request) {
        Student student = studentRepository.findByUsername(username);
        if (student == null) throw ApiException.notFound("Student not found");

        Semester sem = student.getSemesters().stream().filter(Semester::isActive).findFirst().orElse(null);
        if (sem == null || sem.hasEndedBy(LocalDate.now())) throw ApiException.conflict(StudentService.NO_ACTIVE_SEMESTER);

        String subjectName = request.subjectName().trim();
        Subject subject = subjectRepository.findBySubjectNameIgnoreCaseAndUsernameAndSemesterNumber(
                subjectName, username, sem.getNumber());
        if (subject == null) {
            subject = new Subject();
            subject.setUsername(username);
            subject.setSemesterNumber(sem.getNumber());
            subject.setSubjectName(subjectName);
            subject.setCredits(3);
            subject.setConstant(false);
            subject.setCalibrationComplete(false);
            subject.setCalibrationStartDate(LocalDate.now().toString());
            subject = subjectRepository.save(subject);
            if (!sem.getSubjectNames().contains(subject.getSubjectName())) {
                sem.getSubjectNames().add(subject.getSubjectName());
                studentRepository.save(student);
            }
        }

        // One rating per subject per week: re-submitting a week replaces the earlier rating
        int week = request.weekNumber();
        calibrationLogRepository.deleteAll(calibrationLogRepository.findBySubjectIdAndWeekNumber(subject.getId(), week));

        CalibrationLog log = new CalibrationLog();
        log.setSubjectId(subject.getId());
        log.setSubjectName(subject.getSubjectName());
        log.setUsername(username);
        log.setSelfReportedDifficulty(request.difficulty());
        log.setStudyHoursThisWeek(request.studyHours());
        log.setWeekNumber(week);
        log.setLoggedDate(LocalDate.now().toString());
        calibrationLogRepository.save(log);

        // Auto-finalizes D_s once both weeks of the calibration phase are logged
        recalculateCalibration(subject);
        return subjectRepository.findById(subject.getId()).orElse(subject);
    }

    // D_s = (sum of self-reported difficulty for weeks 1 and 2 + avg weekly study hours) / credits.
    // Only the latest rating for each week counts, so repeat submissions can't inflate D_s.
    // Does nothing until both weeks have a rating.
    public void recalculateCalibration(Subject subject) {
        List<CalibrationLog> logs = new ArrayList<>(calibrationLogRepository.findBySubjectId(subject.getId()));
        logs.sort(Comparator.comparing(CalibrationLog::getId)); // ObjectIds sort by creation time

        Map<Integer, CalibrationLog> latestPerWeek = new TreeMap<>();
        for (CalibrationLog log : logs) {
            if (log.getWeekNumber() == 1 || log.getWeekNumber() == 2) latestPerWeek.put(log.getWeekNumber(), log);
        }
        if (latestPerWeek.size() < 2) return;

        int sumDifficulty = latestPerWeek.values().stream().mapToInt(CalibrationLog::getSelfReportedDifficulty).sum();
        double avgStudyHours = latestPerWeek.values().stream().mapToDouble(CalibrationLog::getStudyHoursThisWeek).average().orElse(0);
        double ds = (sumDifficulty + avgStudyHours) / Math.max(subject.getCredits(), 1);

        subject.setDifficultyCoefficient(Math.round(ds * 100.0) / 100.0);
        subject.setCalibrationComplete(true);
        subjectRepository.save(subject);
    }

    // ---------- Analytics history ----------

    public void recordSnapshot(Student student, int semesterNumber, double workloadIndex, double workloadPercent,
                                double balanceScore, String burnoutRisk) {
        AnalyticsResult snapshot = new AnalyticsResult();
        snapshot.setUsername(student.getUsername());
        snapshot.setDepartment(student.getDepartment());
        snapshot.setCurrentSemester(semesterNumber);
        snapshot.setDate(LocalDate.now().toString());
        snapshot.setWorkloadIndex(workloadIndex);
        snapshot.setWorkloadPercent(workloadPercent);
        snapshot.setBalanceScore(balanceScore);
        snapshot.setBurnoutRisk(burnoutRisk);

        // One snapshot per student per day: overwrite today's entry instead of stacking duplicates
        analyticsResultRepository.findFirstByUsernameAndDate(student.getUsername(), snapshot.getDate())
                .ifPresent(r -> snapshot.setId(r.getId()));

        analyticsResultRepository.save(snapshot);
    }

    public List<AnalyticsResult> getHistoryFor(String username) {
        return analyticsResultRepository.findByUsernameOrderByDateAsc(username);
    }

    private AnalyticsResult latestSnapshot(String username) {
        return analyticsResultRepository.findFirstByUsernameOrderByDateDesc(username).orElse(null);
    }

    // Workload above 85% of capacity on each of the last 3 snapshots, and those were 3 consecutive days
    // (snapshots only exist for days the student opened the app, so gaps don't count as "straight").
    public boolean hasSustainedOverload(String username) {
        List<AnalyticsResult> history = analyticsResultRepository.findByUsernameOrderByDateAsc(username);
        if (history.size() < 3) return false;
        List<AnalyticsResult> lastThree = history.subList(history.size() - 3, history.size());
        if (!lastThree.stream().allMatch(r -> r.getWorkloadPercent() > 85)) return false;

        LocalDate first = LocalDate.parse(lastThree.get(0).getDate());
        for (int i = 1; i < lastThree.size(); i++) {
            if (!LocalDate.parse(lastThree.get(i).getDate()).equals(first.plusDays(i))) return false;
        }
        return true;
    }

    // ---------- Faculty: class stress heatmap ----------

    public Map<String, Object> getFacultyHeatmap(String department) {
        List<Student> students = (department == null || department.isBlank())
                ? studentRepository.findByRole("STUDENT")
                : studentRepository.findByRoleAndDepartment("STUDENT", department);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Student s : students) {
            AnalyticsResult latest = latestSnapshot(s.getUsername());
            Map<String, Object> row = new HashMap<>();
            row.put("name", s.getName());
            row.put("username", s.getUsername());
            row.put("department", s.getDepartment());
            row.put("semester", s.getActiveSemesterNumber());
            row.put("balanceScore", latest != null ? latest.getBalanceScore() : null);
            row.put("burnoutRisk", latest != null ? latest.getBurnoutRisk() : "NO DATA");
            rows.add(row);
        }

        double avgBalance = rows.stream()
                .filter(r -> r.get("balanceScore") != null)
                .mapToDouble(r -> (double) r.get("balanceScore"))
                .average().orElse(0);

        Map<String, Object> result = new HashMap<>();
        result.put("students", rows);
        result.put("classAverageBalance", Math.round(avgBalance * 10.0) / 10.0);
        result.put("totalStudents", rows.size());
        return result;
    }

    // ---------- Advisor: high-risk student list ----------

    public List<Map<String, Object>> getHighRiskStudents() {
        List<Student> students = studentRepository.findByRole("STUDENT");
        List<Map<String, Object>> result = new ArrayList<>();

        for (Student s : students) {
            AnalyticsResult latest = latestSnapshot(s.getUsername());
            if (latest != null && "HIGH".equals(latest.getBurnoutRisk())) {
                Map<String, Object> row = new HashMap<>();
                row.put("name", s.getName());
                row.put("username", s.getUsername());
                row.put("department", s.getDepartment());
                row.put("balanceScore", latest.getBalanceScore());
                row.put("sustainedOverload", hasSustainedOverload(s.getUsername()));
                result.add(row);
            }
        }
        result.sort(Comparator.comparingDouble(r -> (double) r.get("balanceScore")));
        return result;
    }

    // ---------- Admin: semester report ----------

    public Map<String, Object> getSemesterReport(int semester) {
        List<AnalyticsResult> results = analyticsResultRepository.findByCurrentSemester(semester);

        Map<String, AnalyticsResult> latestPerStudent = new LinkedHashMap<>();
        for (AnalyticsResult r : results) {
            latestPerStudent.merge(r.getUsername(), r, (a, b) -> a.getDate().compareTo(b.getDate()) >= 0 ? a : b);
        }

        Collection<AnalyticsResult> latest = latestPerStudent.values();
        double avgBalance = latest.stream().mapToDouble(AnalyticsResult::getBalanceScore).average().orElse(0);
        double avgWorkload = latest.stream().mapToDouble(AnalyticsResult::getWorkloadIndex).average().orElse(0);

        Map<String, Long> riskDistribution = latest.stream()
                .collect(Collectors.groupingBy(AnalyticsResult::getBurnoutRisk, Collectors.counting()));

        Map<String, Object> report = new HashMap<>();
        report.put("semester", semester);
        report.put("studentCount", latest.size());
        report.put("averageBalanceScore", Math.round(avgBalance * 10.0) / 10.0);
        report.put("averageWorkloadIndex", Math.round(avgWorkload * 10.0) / 10.0);
        report.put("riskDistribution", riskDistribution);
        return report;
    }

    // ---------- Admin: department trend comparison ----------

    public List<Map<String, Object>> getDepartmentTrends() {
        List<Student> students = studentRepository.findByRole("STUDENT");
        Map<String, List<Student>> byDept = students.stream()
                .collect(Collectors.groupingBy(s -> s.getDepartment() == null || s.getDepartment().isBlank() ? "Unassigned" : s.getDepartment()));

        List<Map<String, Object>> trends = new ArrayList<>();
        for (Map.Entry<String, List<Student>> entry : byDept.entrySet()) {
            List<Double> scores = new ArrayList<>();
            for (Student s : entry.getValue()) {
                AnalyticsResult latest = latestSnapshot(s.getUsername());
                if (latest != null) scores.add(latest.getBalanceScore());
            }
            double avg = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            Map<String, Object> row = new HashMap<>();
            row.put("department", entry.getKey());
            row.put("studentCount", entry.getValue().size());
            row.put("averageBalanceScore", Math.round(avg * 10.0) / 10.0);
            trends.add(row);
        }
        return trends;
    }
}
