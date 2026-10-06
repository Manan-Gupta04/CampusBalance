package com.campusbalance.analytics.service;

import com.campusbalance.analytics.model.AnalyticsResult;
import com.campusbalance.analytics.model.WellnessLog;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The five burnout classes and the intervention tier they call for. Each class is a fixed rule
 * over data the app already stores, and every flag carries the reason it fired, so a student or
 * advisor can trace it back to the logged inputs.
 *
 *   PHY  Physical Exhaustion    — repeated check-ins with under 5h sleep or very low energy     (Tier 2)
 *   EMO  Emotional Exhaustion   — "Stressed" is the dominant mood over the last 7 days           (Tier 2)
 *   COG  Cognitive Overload     — coursework load heavily outweighs recovery                     (Tier 1)
 *   ORG  Organizational Issues  — big day-to-day swings in Workload % (cramming)                 (Tier 1)
 *   CTRL Loss of Control        — Sustained Overload: Workload % > 85 on 3 consecutive days      (Tier 3)
 */
public final class BurnoutClassifier {

    public enum BurnoutClass {
        PHY("Physical Exhaustion", 2),
        EMO("Emotional Exhaustion", 2),
        COG("Cognitive Overload", 1),
        ORG("Organizational Issues", 1),
        CTRL("Loss of Control", 3);

        public final String label;
        public final int tier;

        BurnoutClass(String label, int tier) {
            this.label = label;
            this.tier = tier;
        }
    }

    /** One fired class, in the shape the frontend shows it. */
    public record Flag(String code, String name, int tier, String reason) {
        static Flag of(BurnoutClass c, String reason) {
            return new Flag(c.name(), c.label, c.tier, reason);
        }
    }

    /** All fired classes and the highest intervention tier among them (0 = none). */
    public record Assessment(List<Flag> flags, int tier) {
        public List<String> codes() {
            return flags.stream().map(Flag::code).toList();
        }
    }

    // PHY: at least 3 of the 5 most recent check-ins (from the last 10 days) are "depleted"
    static final int PHY_RECENT_CHECKINS = 5;
    static final int PHY_MIN_DEPLETED = 3;
    static final int PHY_LOOKBACK_DAYS = 10;
    static final double PHY_MIN_SLEEP_HOURS = 5;   // under this the recovery bonus goes negative
    static final int PHY_LOW_ENERGY = 2;

    // EMO: more than half of the check-ins in the last 7 days are "Stressed" (needs 3+ check-ins)
    static final int EMO_LOOKBACK_DAYS = 7;
    static final int EMO_MIN_CHECKINS = 3;

    // COG: coursework load is at least half of weekly capacity and at least 3x the recovery relief
    static final double COG_MIN_COURSEWORK = WorkloadCalculator.MAX_CAPACITY_HOURS / 2;
    static final double COG_MIN_RATIO = 3;

    // ORG: Workload % jumped by 50+ points between two neighbouring snapshots in the last 14 days
    static final int ORG_LOOKBACK_DAYS = 14;
    static final double ORG_MIN_SWING = 50;

    private BurnoutClassifier() {
    }

    /**
     * @param logs              the student's wellness check-ins (any order)
     * @param recentNewestFirst the student's latest Balance Score snapshots, newest first
     * @param courseworkLoad    current coursework load (0 if there's no active semester)
     * @param recoveryLoad      current recovery relief (0 if there's no active semester)
     */
    public static Assessment assess(List<WellnessLog> logs, List<AnalyticsResult> recentNewestFirst,
                                    double courseworkLoad, double recoveryLoad, LocalDate today) {
        List<WellnessLog> newestLogs = logs.stream()
                .filter(l -> parseDate(l.getDate()) != null)
                .sorted(Comparator.comparing((WellnessLog l) -> l.getDate()).reversed())
                .toList();
        List<AnalyticsResult> snapshots = recentNewestFirst == null ? List.of() : recentNewestFirst;

        List<Flag> flags = new ArrayList<>();
        physicalExhaustion(newestLogs, today, flags);
        emotionalExhaustion(newestLogs, today, flags);
        cognitiveOverload(courseworkLoad, recoveryLoad, flags);
        organizationalIssues(snapshots, today, flags);
        lossOfControl(snapshots, flags);

        int tier = flags.stream().mapToInt(Flag::tier).max().orElse(0);
        return new Assessment(flags, tier);
    }

    private static void physicalExhaustion(List<WellnessLog> newestLogs, LocalDate today, List<Flag> flags) {
        LocalDate since = today.minusDays(PHY_LOOKBACK_DAYS - 1);
        List<WellnessLog> recent = newestLogs.stream()
                .filter(l -> !parseDate(l.getDate()).isBefore(since))
                .limit(PHY_RECENT_CHECKINS)
                .toList();
        long depleted = recent.stream()
                .filter(l -> l.getSleepHours() < PHY_MIN_SLEEP_HOURS || l.getEnergyLevel() <= PHY_LOW_ENERGY)
                .count();
        if (depleted >= PHY_MIN_DEPLETED) {
            flags.add(Flag.of(BurnoutClass.PHY, depleted + " of your last " + recent.size()
                    + " check-ins had under 5 hours of sleep or very low energy."));
        }
    }

    private static void emotionalExhaustion(List<WellnessLog> newestLogs, LocalDate today, List<Flag> flags) {
        LocalDate since = today.minusDays(EMO_LOOKBACK_DAYS - 1);
        List<WellnessLog> week = newestLogs.stream().filter(l -> !parseDate(l.getDate()).isBefore(since)).toList();
        long stressed = week.stream().filter(WorkloadCalculator::isStressed).count();
        if (week.size() >= EMO_MIN_CHECKINS && stressed * 2 > week.size()) {
            flags.add(Flag.of(BurnoutClass.EMO, "You felt stressed on " + stressed + " of your "
                    + week.size() + " check-ins in the last 7 days."));
        }
    }

    private static void cognitiveOverload(double coursework, double recovery, List<Flag> flags) {
        if (coursework < COG_MIN_COURSEWORK || coursework < COG_MIN_RATIO * recovery) return;
        String reason = recovery > 0
                ? String.format("Your coursework load (%.1f) is %.1f× your recovery relief (%.0f).", coursework, coursework / recovery, recovery)
                : String.format("Your coursework load is %.1f with no active recovery activities to offset it.", coursework);
        flags.add(Flag.of(BurnoutClass.COG, reason));
    }

    private static void organizationalIssues(List<AnalyticsResult> newestFirst, LocalDate today, List<Flag> flags) {
        LocalDate since = today.minusDays(ORG_LOOKBACK_DAYS - 1);
        List<AnalyticsResult> recent = newestFirst.stream()
                .filter(r -> parseDate(r.getDate()) != null && !parseDate(r.getDate()).isBefore(since))
                .toList();
        AnalyticsResult swingFrom = null, swingTo = null;
        double biggest = 0;
        for (int i = 0; i + 1 < recent.size(); i++) {
            AnalyticsResult newer = recent.get(i), older = recent.get(i + 1);
            double swing = Math.abs(newer.getWorkloadPercent() - older.getWorkloadPercent());
            if (swing > biggest) {
                biggest = swing;
                swingFrom = older;
                swingTo = newer;
            }
        }
        if (biggest >= ORG_MIN_SWING) {
            flags.add(Flag.of(BurnoutClass.ORG, String.format(
                    "Your workload swung from %.0f%% (%s) to %.0f%% (%s) — a sign of cramming before deadlines.",
                    swingFrom.getWorkloadPercent(), swingFrom.getDate(), swingTo.getWorkloadPercent(), swingTo.getDate())));
        }
    }

    private static void lossOfControl(List<AnalyticsResult> newestFirst, List<Flag> flags) {
        if (AnalyticsService.isSustainedOverload(newestFirst)) {
            flags.add(Flag.of(BurnoutClass.CTRL, "Your workload has been above 85% of capacity for 3 days in a row (latest: "
                    + newestFirst.get(0).getDate() + ")."));
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
