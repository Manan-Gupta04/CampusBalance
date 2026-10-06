package com.campusbalance.analytics.service;

import com.campusbalance.analytics.model.AnalyticsResult;
import com.campusbalance.analytics.model.WellnessLog;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BurnoutClassifierTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    private static WellnessLog log(int daysAgo, double sleep, int energy, String mood) {
        return new WellnessLog(TODAY.minusDays(daysAgo).toString(), sleep, 4, mood, energy, false);
    }

    private static WellnessLog healthyLog(int daysAgo) {
        return log(daysAgo, 7.5, 4, "😄 Good");
    }

    // Newest first, one per day ending today
    private static List<AnalyticsResult> snapshots(double... workloadPercentNewestFirst) {
        List<AnalyticsResult> list = new ArrayList<>();
        for (int i = 0; i < workloadPercentNewestFirst.length; i++) {
            AnalyticsResult r = new AnalyticsResult();
            r.setDate(TODAY.minusDays(i).toString());
            r.setWorkloadPercent(workloadPercentNewestFirst[i]);
            list.add(r);
        }
        return list;
    }

    private static List<String> codes(List<WellnessLog> logs, List<AnalyticsResult> snaps, double coursework, double recovery) {
        return BurnoutClassifier.assess(logs, snaps, coursework, recovery, TODAY).codes();
    }

    @Test
    void healthyStudentHasNoSignals() {
        BurnoutClassifier.Assessment a = BurnoutClassifier.assess(
                List.of(healthyLog(0), healthyLog(1), healthyLog(2)), snapshots(40, 42, 38), 20, 10, TODAY);
        assertThat(a.flags()).isEmpty();
        assertThat(a.tier()).isZero();
    }

    @Test
    void physicalExhaustionNeedsRepeatedShortNightsOrLowEnergy() {
        List<WellnessLog> logs = List.of(log(0, 4.5, 3, "😐 Neutral"), log(1, 6, 1, "😐 Neutral"),
                log(2, 4, 3, "😐 Neutral"), healthyLog(3), healthyLog(4));
        assertThat(codes(logs, List.of(), 0, 0)).containsExactly("PHY");

        // Only two depleted days out of five is not "repeated"
        List<WellnessLog> twoBad = List.of(log(0, 4.5, 3, "😐 Neutral"), log(1, 4, 3, "😐 Neutral"),
                healthyLog(2), healthyLog(3), healthyLog(4));
        assertThat(codes(twoBad, List.of(), 0, 0)).isEmpty();
    }

    @Test
    void oldCheckInsDoNotCount() {
        List<WellnessLog> stale = List.of(log(20, 4, 1, "😫 Stressed"), log(21, 4, 1, "😫 Stressed"), log(22, 4, 1, "😫 Stressed"));
        assertThat(codes(stale, List.of(), 0, 0)).isEmpty();
    }

    @Test
    void emotionalExhaustionWhenStressDominatesTheWeek() {
        List<WellnessLog> logs = List.of(log(0, 7, 4, "😫 Stressed"), log(2, 7, 4, "😫 Stressed"),
                log(4, 7, 4, "😫 Stressed / Anxious"), healthyLog(5));
        assertThat(codes(logs, List.of(), 0, 0)).containsExactly("EMO");

        // Half isn't "dominant"
        List<WellnessLog> even = List.of(log(0, 7, 4, "😫 Stressed"), log(1, 7, 4, "😫 Stressed"), healthyLog(2), healthyLog(3));
        assertThat(codes(even, List.of(), 0, 0)).isEmpty();
    }

    @Test
    void cognitiveOverloadWhenCourseworkDwarfsRecovery() {
        assertThat(codes(List.of(), List.of(), 52.2, 3)).containsExactly("COG");
        assertThat(codes(List.of(), List.of(), 40, 0)).containsExactly("COG");
        assertThat(codes(List.of(), List.of(), 52.2, 20)).isEmpty();   // ratio under 3
        assertThat(codes(List.of(), List.of(), 20, 0)).isEmpty();      // load under half of capacity
    }

    @Test
    void organizationalIssuesOnBigDayToDaySwings() {
        assertThat(codes(List.of(), snapshots(150, 10, 60), 0, 0)).contains("ORG");
        assertThat(codes(List.of(), snapshots(60, 50, 70, 55), 0, 0)).isEmpty();
    }

    @Test
    void lossOfControlIsTierThreeAndOutranksTheRest() {
        BurnoutClassifier.Assessment a = BurnoutClassifier.assess(
                List.of(log(0, 4, 1, "😫 Stressed"), log(1, 4, 1, "😫 Stressed"), log(2, 4, 1, "😫 Stressed")),
                snapshots(153, 120, 95), 52.2, 3, TODAY);
        assertThat(a.codes()).containsExactlyInAnyOrder("PHY", "EMO", "COG", "CTRL");
        assertThat(a.tier()).isEqualTo(3);
        assertThat(a.flags()).allSatisfy(f -> assertThat(f.reason()).isNotBlank());
    }
}
