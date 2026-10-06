package com.campusbalance.analytics.migration;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface AppliedMigrationRepository extends MongoRepository<AppliedMigration, String> {
}
