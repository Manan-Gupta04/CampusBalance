package com.campusbalance.analytics.controller;

import com.campusbalance.analytics.model.Student;
import com.campusbalance.analytics.service.AnalyticsService;
import com.campusbalance.analytics.service.StudentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminController {

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private StudentService studentService;

    // Admin provisions a faculty account — faculty never self-register
    @PostMapping("/create-faculty")
    public ResponseEntity<?> createFaculty(@RequestBody Student s) {
        boolean created = studentService.createFaculty(s);
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