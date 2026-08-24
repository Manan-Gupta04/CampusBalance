package com.campusbalance.analytics.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Activity {
    private String name;
    private int weight;            // 1-10 — stress weight (Focus) or relief weight (Recovery)
    private boolean custom;        // true if the student typed this instead of picking a preset
    private boolean active = true; // paused/resumed without deleting history
}