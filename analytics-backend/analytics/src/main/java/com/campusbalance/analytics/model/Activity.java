package com.campusbalance.analytics.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Activity {
    @NotBlank(message = "Activity name is required")
    @Size(max = 60, message = "Activity name is too long")
    private String name;

    @Min(value = 1, message = "Weight must be between 1 and 10")
    @Max(value = 10, message = "Weight must be between 1 and 10")
    private int weight;            // 1-10 — stress weight (Focus) or relief weight (Recovery)

    private boolean custom;        // true if the student typed this instead of picking a preset
    private boolean active = true; // paused/resumed without deleting history
}
