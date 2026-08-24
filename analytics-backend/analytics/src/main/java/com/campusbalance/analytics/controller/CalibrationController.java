package com.campusbalance.analytics.controller;

import com.campusbalance.analytics.model.Subject;
import com.campusbalance.analytics.service.AnalyticsService;
import com.campusbalance.analytics.service.StudentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/calibration")
@CrossOrigin(origins = "*")
public class CalibrationController {

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private StudentService studentService;

    // Body: { subjectName, difficulty (1-5), studyHours, weekNumber (1 or 2) }
    // The subject should already exist in the student's active semester (added via the Subjects tab).
    @PostMapping("/{username}")
    public Map<String, Object> submitCalibration(@PathVariable String username, @RequestBody Map<String, Object> body) {
        String subjectName = (String) body.get("subjectName");
        int difficulty = ((Number) body.getOrDefault("difficulty", 3)).intValue();
        double studyHours = ((Number) body.getOrDefault("studyHours", 0)).doubleValue();
        int weekNumber = ((Number) body.getOrDefault("weekNumber", 1)).intValue();

        return analyticsService.submitCalibrationLog(username, subjectName, difficulty, studyHours, weekNumber);
    }

    // Subjects belonging to this student's active semester
    @GetMapping("/subjects")
    public List<Subject> getSubjects(@RequestParam String username) {
        return studentService.getSubjects(username);
    }
}