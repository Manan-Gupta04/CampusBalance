package com.campusbalance.analytics.controller;

import com.campusbalance.analytics.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/faculty")
@CrossOrigin(origins = "*")
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
