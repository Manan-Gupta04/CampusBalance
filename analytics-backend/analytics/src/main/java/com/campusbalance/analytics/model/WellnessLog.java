package com.campusbalance.analytics.model;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WellnessLog {
    private String date;
    private double sleepHours;
    private double studyHours;
    private String mood; // 😄, 😐, 😫
    private int energyLevel; // 1-5
    private boolean physicalActivity;
}
