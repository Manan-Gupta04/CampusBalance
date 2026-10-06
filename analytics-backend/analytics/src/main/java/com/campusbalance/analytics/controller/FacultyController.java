package com.campusbalance.analytics.controller;

import com.campusbalance.analytics.model.Departments;
import com.campusbalance.analytics.model.Student;
import com.campusbalance.analytics.service.AnalyticsService;
import com.campusbalance.analytics.service.ApiException;
import com.campusbalance.analytics.service.StudentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// Every route here requires a FACULTY or ADMIN token (see SecurityConfig)
@RestController
@RequestMapping("/api/faculty")
public class FacultyController {

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private StudentService studentService;

    // Faculty only ever see their own department's students, whatever they ask for;
    // admins see every department, or the one they ask for.
    private String departmentScope(Authentication authentication, String requested) {
        Student account = studentService.getAccount(authentication.getName());
        if (!"FACULTY".equals(account.getRole())) return requested;

        String own = Departments.normalize(account.getDepartment());
        if (own == null || own.isBlank()) {
            throw ApiException.conflict("Your account has no department yet — ask the admin to set one.");
        }
        return own;
    }

    @GetMapping("/heatmap")
    public Map<String, Object> getHeatmap(Authentication authentication, @RequestParam(required = false) String department) {
        return analyticsService.getFacultyHeatmap(departmentScope(authentication, department));
    }

    @GetMapping("/high-risk")
    public List<Map<String, Object>> getHighRisk(Authentication authentication, @RequestParam(required = false) String department) {
        return analyticsService.getHighRiskStudents(departmentScope(authentication, department));
    }
}
