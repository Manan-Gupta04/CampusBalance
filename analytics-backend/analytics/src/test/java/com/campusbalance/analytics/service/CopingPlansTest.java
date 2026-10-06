package com.campusbalance.analytics.service;

import com.campusbalance.analytics.model.CopingPlan;
import com.campusbalance.analytics.model.WellnessLog;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CopingPlansTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 1);

    private static WellnessLog night(LocalDate day, double sleep) {
        return new WellnessLog(day.toString(), sleep, 3, "😐 Neutral", 3, false);
    }

    @Test
    void sleepPlanTracksEachWeekAgainstItsGoal() {
        List<WellnessLog> logs = new ArrayList<>();
        // Week 1: 4 nights of 6h+ (goal: 4) -> done
        for (int d = 0; d < 4; d++) logs.add(night(START.plusDays(d), 6.2));
        // Week 2: only 3 nights of 6.5h+ (goal: 5) -> missed
        for (int d = 7; d < 10; d++) logs.add(night(START.plusDays(d), 6.6));
        // Week 3: 2 nights so far, still running
        for (int d = 14; d < 16; d++) logs.add(night(START.plusDays(d), 7.5));

        CopingPlans.PlanProgress p = CopingPlans.progress(
                new CopingPlan("SLEEP", START.toString(), null), logs, START.plusDays(16));

        assertThat(p.status()).isEqualTo("ACTIVE");
        assertThat(p.currentWeek()).isEqualTo(3);
        assertThat(p.weeks()).extracting(CopingPlans.WeekProgress::status)
                .containsExactly("DONE", "MISSED", "IN_PROGRESS", "UPCOMING");
        assertThat(p.weeks().get(2).achieved()).isEqualTo(2);
    }

    @Test
    void planCompletesAfterFourWeeksOrWhenEndedEarly() {
        CopingPlan plan = new CopingPlan("STRESS", START.toString(), null);
        assertThat(CopingPlans.isRunning(plan, START.plusDays(27))).isTrue();
        assertThat(CopingPlans.isRunning(plan, START.plusDays(28))).isFalse();
        assertThat(CopingPlans.progress(plan, List.of(), START.plusDays(30)).status()).isEqualTo("COMPLETED");

        plan.setEndedDate(START.plusDays(3).toString());
        assertThat(CopingPlans.isRunning(plan, START.plusDays(4))).isFalse();
        assertThat(CopingPlans.progress(plan, List.of(), START.plusDays(4)).status()).isEqualTo("ENDED_EARLY");
    }
}
