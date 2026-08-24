package com.campusbalance.analytics.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Semester {
    private int number;
    private String startDate; // yyyy-MM-dd
    private String endDate;   // yyyy-MM-dd — semester auto-archives once "today" passes this date
    private boolean active = true;

    private List<String> subjectNames = new ArrayList<>();
    private List<Student.Assignment> assignments = new ArrayList<>();
    private List<WellnessLog> wellnessLogs = new ArrayList<>();
    private List<Activity> focusActivities = new ArrayList<>();
    private List<Activity> recoveryActivities = new ArrayList<>();

    // Set once the semester is archived (manually ended, or auto-ended by date)
    private Double finalBalanceScore;
    private String archivedDate;
}