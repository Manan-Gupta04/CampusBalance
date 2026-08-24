package com.campusbalance.analytics.model;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SemesterHistory {
    private int semesterNumber;
    private double finalBalanceScore;
    private String completionDate;
}
