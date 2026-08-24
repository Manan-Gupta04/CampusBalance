package com.campusbalance.analytics.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "subjects")
public class Subject {
    @Id
    private String id;
    private String username;       // which student this subject belongs to
    private int semesterNumber;    // which of that student's semesters it belongs to
    private String subjectName;
    private String department;
    private int credits;
    private boolean isConstant; // true for fixed BTech overhead like DSA Prep, Lab Files
    private boolean calibrationComplete;
    private Double difficultyCoefficient; // D_s — null until the 2-week calibration phase finishes
    private String calibrationStartDate;
}