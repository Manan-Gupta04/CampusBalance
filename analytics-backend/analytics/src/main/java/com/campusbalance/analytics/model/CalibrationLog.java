package com.campusbalance.analytics.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "calibration_logs")
public class CalibrationLog {
    @Id
    private String id;
    private String subjectId;
    private String subjectName;
    private String username;
    private int selfReportedDifficulty; // 1-5
    private double studyHoursThisWeek;
    private int weekNumber; // 1 or 2 (Calibration Phase)
    private String loggedDate;
}
