package com.campusbalance.analytics.repository;

import com.campusbalance.analytics.model.AnalyticsResult;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AnalyticsResultRepository extends MongoRepository<AnalyticsResult, String> {
    List<AnalyticsResult> findByUsernameOrderByDateAsc(String username);
    List<AnalyticsResult> findByDepartment(String department);
    List<AnalyticsResult> findByCurrentSemester(int currentSemester);
}
