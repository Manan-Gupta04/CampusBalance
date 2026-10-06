package com.campusbalance.analytics.repository;

import com.campusbalance.analytics.model.AnalyticsResult;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface AnalyticsResultRepository extends MongoRepository<AnalyticsResult, String> {
    List<AnalyticsResult> findByUsernameOrderByDateAsc(String username);
    Optional<AnalyticsResult> findFirstByUsernameAndDate(String username, String date);
    Optional<AnalyticsResult> findFirstByUsernameOrderByDateDesc(String username);
    List<AnalyticsResult> findByCurrentSemester(int currentSemester);
}
