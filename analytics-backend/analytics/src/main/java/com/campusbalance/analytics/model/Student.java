package com.campusbalance.analytics.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.*;

@Data
@Document(collection = "students")
public class Student {
    @Id
    private String id;
    private String name;
    private String username;
    private String password;

    private String role = "STUDENT"; // STUDENT, FACULTY, ADMIN
    private String department;
    private String batchYear;

    private String btechStartDate;
    private String btechEndDate;

    // Semester Lifecycle — a student works inside exactly one active semester at a time.
    // 0 means no semester has been created yet. Past semesters stay in `semesters` forever,
    // just marked inactive/archived, so they can be revisited from the Profile view.
    private int activeSemesterNumber = 0;
    private List<Semester> semesters = new ArrayList<>();

    // Engagement
    private int totalXP = 0;
    private int dailyStreak = 0;

    @Data
    public static class Assignment {
        private String title;
        private String subject;
        private String status; // "Pending" or "Completed"
        private String difficulty;
        private int priority;
        private String deadline;
        private int difficultyWeight;
        private String completedAt;
    }
}