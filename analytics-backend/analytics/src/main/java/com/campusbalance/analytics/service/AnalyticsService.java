package com.campusbalance.analytics.service;

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
    public Map<String, Object> submitCalibrationLog(String username, String subjectName,
                                                      int difficulty, double studyHours, int weekNumber) {
        Map<String, Object> result = new HashMap<>();
        Student student = studentRepository.findByUsername(username);
        if (student == null) { result.put("error", "Student not found"); return result; }

        Semester sem = student.getSemesters().stream().filter(Semester::isActive).findFirst().orElse(null);
        if (sem == null) { result.put("error", "No active semester"); return result; }

        if (subjectName == null || subjectName.isBlank()) { result.put("error", "Subject name is required"); return result; }

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

        CalibrationLog log = new CalibrationLog();
        log.setSubjectId(subject.getId());
        log.setSubjectName(subject.getSubjectName());
        log.setUsername(username);
        log.setSelfReportedDifficulty(Math.max(1, Math.min(5, difficulty)));
        log.setStudyHoursThisWeek(studyHours);
        log.setWeekNumber(weekNumber);
        log.setLoggedDate(LocalDate.now().toString());
        calibrationLogRepository.save(log);

        // Auto-finalize D_s once we have logs spanning both weeks of the calibration phase
        boolean hasWeek1 = !calibrationLogRepository.findBySubjectIdAndWeekNumber(subject.getId(), 1).isEmpty();
        boolean hasWeek2 = !calibrationLogRepository.findBySubjectIdAndWeekNumber(subject.getId(), 2).isEmpty();
        if (hasWeek1 && hasWeek2) {
            finalizeCalibration(subject);
        }

        result.put("subject", subjectRepository.findById(subject.getId()).orElse(subject));
        result.put("message", "Calibration log recorded");
        return result;
    }

    private void finalizeCalibration(Subject subject) {
        List<CalibrationLog> logs = calibrationLogRepository.findBySubjectId(subject.getId());
        if (logs.isEmpty()) return;

        int sumDifficulty = logs.stream().mapToInt(CalibrationLog::getSelfReportedDifficulty).sum();
        double avgStudyHours = logs.stream().mapToDouble(CalibrationLog::getStudyHoursThisWeek).average().orElse(0);

        // D_s = (sum(self-reported difficulty) + avg study hours per week) / credits
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
        List<AnalyticsResult> existing = analyticsResultRepository.findByUsernameOrderByDateAsc(student.getUsername());
        existing.stream()
                .filter(r -> r.getDate().equals(snapshot.getDate()))
                .findFirst()
                .ifPresent(r -> snapshot.setId(r.getId()));

        analyticsResultRepository.save(snapshot);
    }

    public List<AnalyticsResult> getHistoryFor(String username) {
        return analyticsResultRepository.findByUsernameOrderByDateAsc(username);
    }

    public boolean hasSustainedOverload(String username) {
        List<AnalyticsResult> history = analyticsResultRepository.findByUsernameOrderByDateAsc(username);
        if (history.size() < 3) return false;
        List<AnalyticsResult> lastThree = history.subList(history.size() - 3, history.size());
        return lastThree.stream().allMatch(r -> r.getWorkloadPercent() > 85);
    }

    // ---------- Faculty: class stress heatmap ----------

    public Map<String, Object> getFacultyHeatmap(String department) {
        List<Student> students = (department == null || department.isBlank())
                ? studentRepository.findByRole("STUDENT")
                : studentRepository.findByRoleAndDepartment("STUDENT", department);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Student s : students) {
            List<AnalyticsResult> history = analyticsResultRepository.findByUsernameOrderByDateAsc(s.getUsername());
            AnalyticsResult latest = history.isEmpty() ? null : history.get(history.size() - 1);
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
            List<AnalyticsResult> history = analyticsResultRepository.findByUsernameOrderByDateAsc(s.getUsername());
            if (history.isEmpty()) continue;
            AnalyticsResult latest = history.get(history.size() - 1);
            if ("HIGH".equals(latest.getBurnoutRisk())) {
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
                List<AnalyticsResult> history = analyticsResultRepository.findByUsernameOrderByDateAsc(s.getUsername());
                if (!history.isEmpty()) scores.add(history.get(history.size() - 1).getBalanceScore());
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