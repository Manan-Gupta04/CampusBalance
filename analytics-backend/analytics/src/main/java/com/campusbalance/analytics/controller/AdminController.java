package com.campusbalance.analytics.controller;

import com.campusbalance.analytics.dto.AccountRequest;
import com.campusbalance.analytics.service.AnalyticsService;
import com.campusbalance.analytics.service.StudentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// Every route here requires an ADMIN token (see SecurityConfig)
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private StudentService studentService;

    // Admin provisions a faculty account — faculty never self-register
    @PostMapping("/create-faculty")
    public ResponseEntity<?> createFaculty(@Valid @RequestBody AccountRequest request) {
        boolean created = studentService.createFaculty(request);
        if (created) {
            return ResponseEntity.ok("Faculty account created");
        } else {
            return ResponseEntity.status(409).body("That username is already taken.");
        }
    }

    @GetMapping("/report/{semester}")
    public Map<String, Object> getSemesterReport(@PathVariable int semester) {
        return analyticsService.getSemesterReport(semester);
    }

    @GetMapping("/trends")
    public List<Map<String, Object>> getDepartmentTrends() {
        return analyticsService.getDepartmentTrends();
    }
}
