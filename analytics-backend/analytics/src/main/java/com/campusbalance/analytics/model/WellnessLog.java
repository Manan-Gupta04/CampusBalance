package com.campusbalance.analytics.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
public class WellnessLog {
    private String date; // set by the server, never trusted from the client

    @DecimalMin(value = "0", message = "Sleep hours must be between 0 and 24")
    @DecimalMax(value = "24", message = "Sleep hours must be between 0 and 24")
    private double sleepHours;

    @DecimalMin(value = "0", message = "Study hours must be between 0 and 24")
    @DecimalMax(value = "24", message = "Study hours must be between 0 and 24")
    private double studyHours;

    @NotBlank(message = "Mood is required")
    @Size(max = 40, message = "Mood is too long")
    private String mood; // 😄, 😐, 😫

    @Min(value = 1, message = "Energy level must be between 1 and 5")
    @Max(value = 5, message = "Energy level must be between 1 and 5")
    private int energyLevel; // 1-5

    private boolean physicalActivity;
}
