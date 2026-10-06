package com.campusbalance.analytics.service;

import com.campusbalance.analytics.model.AnalyticsResult;
import com.campusbalance.analytics.model.CalibrationLog;
import com.campusbalance.analytics.model.Subject;
import com.campusbalance.analytics.repository.AnalyticsResultRepository;
import com.campusbalance.analytics.repository.CalibrationLogRepository;
import com.campusbalance.analytics.repository.StudentRepository;
import com.campusbalance.analytics.repository.SubjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private SubjectRepository subjectRepository;

    @Mock
    private CalibrationLogRepository calibrationLogRepository;

    @Mock
    private AnalyticsResultRepository analyticsResultRepository;

    @Mock
    private StudentRepository studentRepository;

    @InjectMocks
    private AnalyticsService service;

    private static CalibrationLog rating(String id, int week, int difficulty, double hours) {
        CalibrationLog log = new CalibrationLog();
        log.setId(id);
        log.setWeekNumber(week);
        log.setSelfReportedDifficulty(difficulty);
        log.setStudyHoursThisWeek(hours);
        return log;
    }

    private static Subject subjectWithCredits(int credits) {
        Subject s = new Subject();
        s.setId("subj");
        s.setCredits(credits);
        return s;
    }

    @Test
    void difficultyCoefficientUsesOnlyTheLatestRatingPerWeek() {
        Subject subject = subjectWithCredits(4);
        // Week 1 was rated twice (ids sort by creation time) — only the later rating may count
        when(calibrationLogRepository.findBySubjectId("subj")).thenReturn(List.of(
                rating("c", 1, 2, 4), rating("a", 1, 5, 10), rating("b", 2, 3, 6)));

        service.recalculateCalibration(subject);

        // (2 + 3 + avg(4, 6)) / 4 credits
        assertThat(subject.getDifficultyCoefficient()).isEqualTo(2.5);
        assertThat(subject.isCalibrationComplete()).isTrue();
    }

    @Test
    void calibrationWaitsForBothWeeks() {
        Subject subject = subjectWithCredits(3);
        when(calibrationLogRepository.findBySubjectId("subj")).thenReturn(List.of(rating("a", 1, 4, 5)));

        service.recalculateCalibration(subject);

        assertThat(subject.isCalibrationComplete()).isFalse();
        verify(subjectRepository, never()).save(any());
    }

    private static AnalyticsResult snapshot(LocalDate date, double workloadPercent) {
        AnalyticsResult r = new AnalyticsResult();
        r.setDate(date.toString());
        r.setWorkloadPercent(workloadPercent);
        return r;
    }

    @Test
    void sustainedOverloadNeedsThreeConsecutiveOverloadedDays() {
        LocalDate d = LocalDate.of(2026, 10, 10);
        when(analyticsResultRepository.findTop3ByUsernameOrderByDateDesc("alice")).thenReturn(List.of(
                snapshot(d, 90), snapshot(d.minusDays(1), 95), snapshot(d.minusDays(2), 88)));
        assertThat(service.hasSustainedOverload("alice")).isTrue();
    }

    @Test
    void overloadWithAGapIsNotSustained() {
        LocalDate d = LocalDate.of(2026, 10, 10);
        when(analyticsResultRepository.findTop3ByUsernameOrderByDateDesc("alice")).thenReturn(List.of(
                snapshot(d, 90), snapshot(d.minusDays(4), 95), snapshot(d.minusDays(5), 88)));
        assertThat(service.hasSustainedOverload("alice")).isFalse();
    }

    @Test
    void oneNormalDayBreaksTheOverload() {
        LocalDate d = LocalDate.of(2026, 10, 10);
        assertThat(AnalyticsService.isSustainedOverload(List.of(
                snapshot(d, 90), snapshot(d.minusDays(1), 60), snapshot(d.minusDays(2), 88)))).isFalse();
    }
}
