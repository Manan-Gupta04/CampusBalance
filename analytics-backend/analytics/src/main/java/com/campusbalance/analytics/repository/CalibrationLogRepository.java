package com.campusbalance.analytics.repository;

import com.campusbalance.analytics.model.CalibrationLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface CalibrationLogRepository extends MongoRepository<CalibrationLog, String> {
    List<CalibrationLog> findBySubjectId(String subjectId);
    List<CalibrationLog> findBySubjectIdAndWeekNumber(String subjectId, int weekNumber);
}
