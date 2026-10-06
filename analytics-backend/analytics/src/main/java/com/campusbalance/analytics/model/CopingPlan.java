package com.campusbalance.analytics.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A student's 4-week coping plan (Tier 2 intervention). Weekly progress is computed from check-ins. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CopingPlan {
    private String type;       // CopingPlans.PlanType name: SLEEP or STRESS
    private String startDate;  // yyyy-MM-dd; the plan runs for 4 weeks from this day
    private String endedDate;  // set if the student ended the plan early
}
