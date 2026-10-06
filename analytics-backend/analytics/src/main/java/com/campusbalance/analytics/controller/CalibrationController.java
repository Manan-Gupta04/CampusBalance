package com.campusbalance.analytics.controller;

import com.campusbalance.analytics.dto.CalibrationRequest;
import com.campusbalance.analytics.model.Subject;
import com.campusbalance.analytics.service.AnalyticsService;
import com.campusbalance.analytics.service.StudentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/calibration")
public class CalibrationController {

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private StudentService studentService;

    // Body: { subjectName, difficulty (1-5), studyHours, weekNumber (1 or 2) }
    // Returns the subject, with its D_s once both weeks are logged.
    @PreAuthorize("#username == authentication.name")
    @PostMapping("/{username}")
    public Subject submitCalibration(@PathVariable String username, @Valid @RequestBody CalibrationRequest request) {
        return analyticsService.submitCalibrationLog(username, request);
    }

    // Subjects belonging to this student's active semester
    @PreAuthorize("#username == authentication.name")
    @GetMapping("/subjects")
    public List<Subject> getSubjects(@RequestParam String username) {
        return studentService.getSubjects(username);
    }
}
