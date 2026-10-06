package com.campusbalance.analytics.migration;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Marks a one-time data migration as done, so it never runs twice. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "applied_migrations")
public class AppliedMigration {
    @Id
    private String name;
    private String appliedAt;
}
