package com.campusbalance.analytics.service;

import com.campusbalance.analytics.model.Activity;
import com.campusbalance.analytics.model.Semester;
import com.campusbalance.analytics.model.Student;
import com.campusbalance.analytics.model.Subject;
import com.campusbalance.analytics.model.WellnessLog;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The CampusBalance scoring formulas (Workload Index, Balance Score, risk tier), shared by the
 * student dashboard and the staff views so both always compute the same numbers.
 */
public final class WorkloadCalculator {

    // "Max Capacity" baseline: 60 hours of productive work per week
    public static final double MAX_CAPACITY_HOURS = 60.0;
    // Approximate combined assignment + lecture load per pending assignment
    public static final double HOURS_PER_PENDING_ASSIGNMENT = 3.0;

    private WorkloadCalculator() {
    }

    public record Score(double workloadIndex, double workloadPercent, double balanceScore, String risk) {}

    // Coursework load from pending assignments only. A subject whose 2-week calibration is complete
    // uses its Difficulty Coefficient (D_s); others fall back to the Easy/Medium/Hard weight.
    // `subjectNamed` looks up the student's subject by name (null if there is none).
    public static double courseworkLoad(Semester sem, Function<String, Subject> subjectNamed) {
        Map<String, List<Student.Assignment>> bySubject = sem.getAssignments().stream()
                .filter(a -> "Pending".equalsIgnoreCase(a.getStatus()))
                .collect(Collectors.groupingBy(a -> a.getSubject() == null || a.getSubject().isBlank() ? "General" : a.getSubject()));

        double total = 0;
        for (Map.Entry<String, List<Student.Assignment>> entry : bySubject.entrySet()) {
            Subject subject = subjectNamed.apply(entry.getKey());
            List<Student.Assignment> tasks = entry.getValue();
            if (subject != null && subject.isCalibrationComplete() && subject.getDifficultyCoefficient() != null) {
                total += subject.getDifficultyCoefficient() * tasks.size() * HOURS_PER_PENDING_ASSIGNMENT;
            } else {
                for (Student.Assignment a : tasks) {
                    int weight = a.getDifficultyWeight() > 0 ? a.getDifficultyWeight() : difficultyToWeight(a.getDifficulty());
                    total += weight * HOURS_PER_PENDING_ASSIGNMENT;
                }
            }
        }
        return total;
    }

    public static int difficultyToWeight(String difficulty) {
        if (difficulty == null) return 1;
        if (difficulty.equalsIgnoreCase("Hard")) return 5;
        if (difficulty.equalsIgnoreCase("Medium")) return 3;
        return 1;
    }

    // Sum of the weights of the activities that are currently active (not paused)
    public static double activeWeight(List<Activity> activities) {
        return activities.stream().filter(Activity::isActive).mapToDouble(Activity::getWeight).sum();
    }

    public static boolean isStressed(WellnessLog log) {
        String mood = log == null || log.getMood() == null ? "" : log.getMood();
        return mood.contains("Stressed") || mood.contains("😫");
    }

    // Mood coefficient (M_c) from a check-in; 1.0 when there is no check-in to go on
    public static double moodCoefficient(WellnessLog log) {
        if (log == null || log.getMood() == null) return 1.0;
        String mood = log.getMood();
        if (isStressed(log)) return 1.5;
        if (mood.contains("Neutral") || mood.contains("😐")) return 1.2;
        if (mood.contains("Good") || mood.contains("😄")) return 0.8;
        return 1.0;
    }

    // Recovery bonus from a check-in: +10 for 7h+ sleep, -15 for under 5h, +5 for energy 4+
    public static double recoveryBonus(WellnessLog log) {
        if (log == null) return 0;
        double bonus = 0;
        if (log.getSleepHours() >= 7) bonus += 10;
        if (log.getSleepHours() < 5) bonus -= 15;
        if (log.getEnergyLevel() >= 4) bonus += 5;
        return bonus;
    }

    public static String riskTier(double balanceScore) {
        return balanceScore < 40 ? "HIGH" : balanceScore < 70 ? "MEDIUM" : "LOW";
    }

    // Workload Index W_i = max(0, coursework + focus - recovery) x M_c, as a % of the 60-hour capacity;
    // Balance Score B = clamp(0, 100, 100 - Workload % + recovery bonus). `latestLog` may be null.
    public static Score score(double courseworkLoad, double focusLoad, double recoveryLoad, WellnessLog latestLog) {
        double rawLoad = Math.max(0, courseworkLoad + focusLoad - recoveryLoad);
        double workloadIndex = rawLoad * moodCoefficient(latestLog);
        double workloadPercent = workloadIndex / MAX_CAPACITY_HOURS * 100.0;
        double balance = Math.min(100, Math.max(0, 100 - workloadPercent + recoveryBonus(latestLog)));
        return new Score(workloadIndex, workloadPercent, balance, riskTier(balance));
    }
}
