package com.campusbalance.analytics.service;

import com.campusbalance.analytics.model.CopingPlan;
import com.campusbalance.analytics.model.WellnessLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Tier 2 scaffolded coping plans: four weeks of gradually harder weekly goals, each measured
 * from the student's own daily check-ins.
 */
public final class CopingPlans {

    public static final int WEEKS = 4;

    record WeeklyGoal(String goal, String tip, int target, Predicate<WellnessLog> counts) {}

    public enum PlanType {
        SLEEP("Sleep Recovery Plan", BurnoutClassifier.BurnoutClass.PHY, List.of(
                new WeeklyGoal("Sleep 6+ hours on at least 4 nights", "Pick a fixed bedtime and set a reminder 30 minutes before it.", 4, l -> l.getSleepHours() >= 6),
                new WeeklyGoal("Sleep 6.5+ hours on at least 5 nights", "No screens in bed — move your phone out of reach.", 5, l -> l.getSleepHours() >= 6.5),
                new WeeklyGoal("Sleep 7+ hours on at least 5 nights", "Avoid caffeine after 4 pm and keep weekend wake-up times close to weekdays.", 5, l -> l.getSleepHours() >= 7),
                new WeeklyGoal("Sleep 7+ hours on at least 6 nights", "Keep the routine that worked — this is your new baseline.", 6, l -> l.getSleepHours() >= 7))),
        STRESS("Stress Recovery Plan", BurnoutClassifier.BurnoutClass.EMO, List.of(
                new WeeklyGoal("Check in on at least 5 days", "Keep at least one Recovery Activity active this week.", 5, l -> true),
                new WeeklyGoal("Feel Neutral or Good on at least 3 days", "Schedule 20 minutes a day for your recovery activity, like a class.", 3, l -> !WorkloadCalculator.isStressed(l)),
                new WeeklyGoal("Feel Neutral or Good on at least 4 days", "Pause your lowest-priority Focus Activity for the rest of the plan.", 4, l -> !WorkloadCalculator.isStressed(l)),
                new WeeklyGoal("Feel Neutral or Good on at least 5 days", "Talk to someone you trust about what's been stressful.", 5, l -> !WorkloadCalculator.isStressed(l))));

        public final String title;
        public final BurnoutClassifier.BurnoutClass forClass;
        final List<WeeklyGoal> goals;

        PlanType(String title, BurnoutClassifier.BurnoutClass forClass, List<WeeklyGoal> goals) {
            this.title = title;
            this.forClass = forClass;
            this.goals = goals;
        }

        // The plan that addresses a Tier 2 burnout class, or null
        public static PlanType forBurnoutClass(String code) {
            for (PlanType t : values()) if (t.forClass.name().equals(code)) return t;
            return null;
        }
    }

    /** status: DONE, MISSED, IN_PROGRESS or UPCOMING */
    public record WeekProgress(int week, String startDate, String endDate, String goal, String tip,
                               long achieved, int target, String status) {}

    /** status: ACTIVE, COMPLETED or ENDED_EARLY */
    public record PlanProgress(String type, String title, String startDate, String endDate, int currentWeek,
                               String status, List<WeekProgress> weeks) {}

    private CopingPlans() {
    }

    public static boolean isRunning(CopingPlan plan, LocalDate today) {
        return plan != null && plan.getEndedDate() == null
                && !today.isAfter(LocalDate.parse(plan.getStartDate()).plusDays(WEEKS * 7L - 1));
    }

    public static PlanProgress progress(CopingPlan plan, List<WellnessLog> allLogs, LocalDate today) {
        PlanType type = PlanType.valueOf(plan.getType());
        LocalDate start = LocalDate.parse(plan.getStartDate());
        LocalDate end = start.plusDays(WEEKS * 7L - 1);
        // An early end freezes the plan on that day
        LocalDate asOf = plan.getEndedDate() != null ? LocalDate.parse(plan.getEndedDate()) : today;

        List<WeekProgress> weeks = new ArrayList<>();
        int currentWeek = 0;
        for (int w = 0; w < WEEKS; w++) {
            WeeklyGoal goal = type.goals.get(w);
            LocalDate from = start.plusDays(w * 7L);
            LocalDate to = from.plusDays(6);
            long achieved = allLogs.stream()
                    .filter(l -> l.getDate() != null)
                    .filter(l -> {
                        LocalDate d = LocalDate.parse(l.getDate());
                        return !d.isBefore(from) && !d.isAfter(to);
                    })
                    .filter(goal.counts())
                    .count();

            String status;
            if (achieved >= goal.target()) status = "DONE";
            else if (asOf.isAfter(to)) status = "MISSED";
            else if (!asOf.isBefore(from)) status = "IN_PROGRESS";
            else status = "UPCOMING";
            if (!asOf.isBefore(from) && !asOf.isAfter(to)) currentWeek = w + 1;

            weeks.add(new WeekProgress(w + 1, from.toString(), to.toString(), goal.goal(), goal.tip(),
                    achieved, goal.target(), status));
        }

        String status = plan.getEndedDate() != null ? "ENDED_EARLY" : today.isAfter(end) ? "COMPLETED" : "ACTIVE";
        return new PlanProgress(type.name(), type.title, start.toString(), end.toString(), currentWeek, status, weeks);
    }
}
