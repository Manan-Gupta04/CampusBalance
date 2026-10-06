package com.campusbalance.analytics.service;

import com.campusbalance.analytics.model.Activity;
import com.campusbalance.analytics.model.Semester;
import com.campusbalance.analytics.model.Student;
import com.campusbalance.analytics.model.WellnessLog;
import com.campusbalance.analytics.repository.StudentRepository;
import com.campusbalance.analytics.repository.SubjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StudentServiceTest {

    @Mock
    private StudentRepository repository;

    @Mock
    private SubjectRepository subjectRepository;

    @Mock
    private AnalyticsService analyticsService;

    @InjectMocks
    private StudentService service;

    private Student student;
    private Semester semester;
    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        semester = new Semester();
        semester.setNumber(1);
        semester.setStartDate(today.minusDays(10).toString());
        semester.setEndDate(today.plusDays(60).toString());
        semester.getSubjectNames().add("Maths");

        student = new Student();
        student.setUsername("alice");
        student.getSemesters().add(semester);
        student.setActiveSemesterNumber(1);
        lenient().when(repository.findByUsername("alice")).thenReturn(student);
    }

    private Student.Assignment addPendingAssignment() {
        Student.Assignment a = new Student.Assignment();
        a.setTitle("HW 1");
        a.setSubject("Maths");
        a.setDifficulty("Hard");
        a.setPriority(2);
        a.setDeadline(today.plusDays(3).toString());
        return service.addAssignment("alice", a);
    }

    private WellnessLog log() {
        return new WellnessLog(null, 7, 4, "😄 Good", 4, false);
    }

    @Test
    void assignmentXpIsAwardedOnlyOnce() {
        Student.Assignment a = addPendingAssignment();
        assertThat(a.getId()).isNotBlank();

        service.submitAssignment("alice", a.getId());
        assertThat(student.getTotalXP()).isEqualTo(50);

        assertThatThrownBy(() -> service.submitAssignment("alice", a.getId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(student.getTotalXP()).isEqualTo(50);
    }

    @Test
    void assignmentMustUseASubjectFromThisSemester() {
        Student.Assignment a = new Student.Assignment();
        a.setTitle("HW");
        a.setSubject("Physics");
        assertThatThrownBy(() -> service.addAssignment("alice", a))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void streakContinuesFromYesterday() {
        student.setDailyStreak(4);
        student.setLastCheckInDate(today.minusDays(1).toString());

        service.addWellnessLog("alice", log());

        assertThat(student.getDailyStreak()).isEqualTo(5);
        assertThat(student.getLastCheckInDate()).isEqualTo(today.toString());
        assertThat(student.getTotalXP()).isEqualTo(20);
    }

    @Test
    void streakRestartsAfterAMissedDay() {
        student.setDailyStreak(9);
        student.setLastCheckInDate(today.minusDays(3).toString());

        service.addWellnessLog("alice", log());

        assertThat(student.getDailyStreak()).isEqualTo(1);
    }

    @Test
    void onlyOneCheckInPerDay() {
        service.addWellnessLog("alice", log());
        assertThatThrownBy(() -> service.addWellnessLog("alice", log()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void duplicateActivityNamesAreRejectedIgnoringCase() {
        service.addFocusActivity("alice", new Activity("DSA Prep", 6, false, true));
        assertThatThrownBy(() -> service.addFocusActivity("alice", new Activity("dsa prep", 6, false, true)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(semester.getFocusActivities()).hasSize(1);
    }

    @Test
    void semesterEndMustBeAfterStart() {
        student.getSemesters().clear();
        Semester request = new Semester();
        request.setStartDate(today.plusDays(30).toString());
        request.setEndDate(today.plusDays(1).toString());

        assertThatThrownBy(() -> service.createSemester("alice", request))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void expiredSemesterIsArchivedWhenTheDashboardLoads() {
        semester.setEndDate(today.minusDays(1).toString());

        Map<String, Object> stats = service.getDashboardStats("alice");

        assertThat(stats.get("hasActiveSemester")).isEqualTo(false);
        assertThat(semester.isActive()).isFalse();
        assertThat(student.getActiveSemesterNumber()).isZero();
        verify(repository).save(student);
    }
}
