package com.campusbalance.analytics.controller;

import com.campusbalance.analytics.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// Every route here requires a FACULTY or ADMIN token (see SecurityConfig)
@RestController
@RequestMapping("/api/faculty")
public class FacultyController {

    @Autowired
    private AnalyticsService analyticsService;

    @GetMapping("/heatmap")
    public Map<String, Object> getHeatmap(@RequestParam(required = false) String department) {
        return analyticsService.getFacultyHeatmap(department);
    }

    @GetMapping("/high-risk")
    public List<Map<String, Object>> getHighRisk() {
        return analyticsService.getHighRiskStudents();
    }
}
