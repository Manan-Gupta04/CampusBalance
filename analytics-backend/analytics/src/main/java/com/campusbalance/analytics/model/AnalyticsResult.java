package com.campusbalance.analytics.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "analytics_results")
public class AnalyticsResult {
    @Id
    private String id;
    private String username;
    private String department;
    private int currentSemester;
    private String date; // one snapshot per student per day
    private double workloadIndex;
    private double workloadPercent; // Wi as a % of Max Capacity (60 hr/week baseline)
    private double balanceScore;
    private String burnoutRisk;
}
