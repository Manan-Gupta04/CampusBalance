package com.campusbalance.analytics.migration;

import com.campusbalance.analytics.model.Semester;
import com.campusbalance.analytics.model.Student;
import com.campusbalance.analytics.model.Subject;
import com.campusbalance.analytics.repository.StudentRepository;
import com.campusbalance.analytics.repository.SubjectRepository;
import com.campusbalance.analytics.service.AnalyticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * One-time fixes for data written by older versions of the app. Each runs once, then is
 * recorded in the applied_migrations collection so later startups skip it.
 */
@Component
@ConditionalOnProperty(name = "app.migrations.enabled", havingValue = "true", matchIfMissing = true)
public class StartupMigrations implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupMigrations.class);

    private final AppliedMigrationRepository applied;
    private final StudentRepository students;
    private final SubjectRepository subjects;
    private final AnalyticsService analyticsService;

    public StartupMigrations(AppliedMigrationRepository applied, StudentRepository students,
                             SubjectRepository subjects, AnalyticsService analyticsService) {
        this.applied = applied;
        this.students = students;
        this.subjects = subjects;
        this.analyticsService = analyticsService;
    }

    @Override
    public void run(ApplicationArguments args) {
        runOnce("2026-10-assignment-ids", this::backfillAssignmentIds);
        runOnce("2026-10-recalculate-difficulty-coefficients", this::recalculateDifficultyCoefficients);
    }

    private void runOnce(String name, Runnable migration) {
        if (applied.existsById(name)) return;
        log.info("Running data migration {}", name);
        migration.run();
        applied.save(new AppliedMigration(name, Instant.now().toString()));
    }

    // Assignments used to be identified by title; they now need an id so duplicates can be told apart
    private void backfillAssignmentIds() {
        int updated = 0;
        for (Student s : students.findAll()) {
            boolean changed = false;
            for (Semester sem : s.getSemesters()) {
                for (Student.Assignment a : sem.getAssignments()) {
                    if (a.getId() == null) {
                        a.setId(UUID.randomUUID().toString());
                        changed = true;
                        updated++;
                    }
                }
            }
            if (changed) students.save(s);
        }
        log.info("Gave {} existing assignments an id", updated);
    }

    // D_s used to sum every calibration log, so repeat submissions inflated it. Recompute with
    // only the latest rating per week.
    private void recalculateDifficultyCoefficients() {
        int count = 0;
        for (Subject subject : subjects.findByCalibrationCompleteTrue()) {
            analyticsService.recalculateCalibration(subject);
            count++;
        }
        log.info("Recalculated D_s for {} calibrated subjects", count);
    }
}
