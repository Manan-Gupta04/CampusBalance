package com.campusbalance.analytics.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** One week's calibration rating for a subject. Re-submitting the same week replaces that week's rating. */
public record CalibrationRequest(
        @NotBlank(message = "Pick a subject to calibrate")
        String subjectName,

        @NotNull(message = "Difficulty is required")
        @Min(value = 1, message = "Difficulty must be between 1 and 5")
        @Max(value = 5, message = "Difficulty must be between 1 and 5")
        Integer difficulty,

        @NotNull(message = "Study hours are required")
        @DecimalMin(value = "0", message = "Study hours must be between 0 and 100")
        @DecimalMax(value = "100", message = "Study hours must be between 0 and 100")
        Double studyHours,

        @NotNull(message = "Calibration week is required")
        @Min(value = 1, message = "Calibration week must be 1 or 2")
        @Max(value = 2, message = "Calibration week must be 1 or 2")
        Integer weekNumber
) {}
