package com.campusbalance.analytics.service;

import com.campusbalance.analytics.model.WellnessLog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WorkloadCalculatorTest {

    @Test
    void workedExampleFromTheMethodology() {
        // Coursework 63.5, Focus 20, Recovery 8, mood Good, 7h+ sleep and energy 4+
        WellnessLog latest = new WellnessLog("2026-09-08", 7.5, 4, "😄 Good / Relaxed", 4, false);
        WorkloadCalculator.Score s = WorkloadCalculator.score(63.5, 20, 8, latest);

        assertThat(s.workloadIndex()).isCloseTo(60.4, within(0.01));     // 75.5 x 0.8
        assertThat(s.workloadPercent()).isCloseTo(100.67, within(0.01));
        assertThat(s.balanceScore()).isCloseTo(14.33, within(0.01));     // 100 - 100.67 + 15
        assertThat(s.risk()).isEqualTo("HIGH");
    }

    @Test
    void recoveryCanNeverMakeTheLoadNegative() {
        WorkloadCalculator.Score s = WorkloadCalculator.score(5, 0, 30, null);
        assertThat(s.workloadIndex()).isZero();
        assertThat(s.balanceScore()).isEqualTo(100);
    }

    @Test
    void shortSleepIsPenalised() {
        WellnessLog tired = new WellnessLog("2026-09-08", 4, 4, "😫 Stressed", 2, false);
        assertThat(WorkloadCalculator.recoveryBonus(tired)).isEqualTo(-15);
        assertThat(WorkloadCalculator.moodCoefficient(tired)).isEqualTo(1.5);
    }
}
