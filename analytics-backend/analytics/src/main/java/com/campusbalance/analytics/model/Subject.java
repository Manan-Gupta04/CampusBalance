package com.campusbalance.analytics.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    @NotBlank(message = "Subject name is required")
    @Size(max = 60, message = "Subject name is too long")
    private String subjectName;

    @Size(max = 40, message = "Department is too long")
    private String department;

    @Min(value = 1, message = "Credits must be between 1 and 10")
    @Max(value = 10, message = "Credits must be between 1 and 10")
    private int credits;

    private boolean isConstant; // true for fixed BTech overhead like DSA Prep, Lab Files
    private boolean calibrationComplete;
    private Double difficultyCoefficient; // D_s — null until the 2-week calibration phase finishes
    private String calibrationStartDate;
}
