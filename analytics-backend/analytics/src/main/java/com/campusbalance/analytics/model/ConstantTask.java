package com.campusbalance.analytics.model;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor 
public class ConstantTask {
    private String taskName; // e.g., "DSA", "Web Dev"
    private int stressWeight; // Static stress value (e.g., 5)
    private boolean isActive;
}
