package com.campusbalance.analytics.controller;

import com.campusbalance.analytics.dto.AccountRequest;
import com.campusbalance.analytics.dto.ChangePasswordRequest;
import com.campusbalance.analytics.dto.LoginRequest;
import com.campusbalance.analytics.dto.LoginResponse;
import com.campusbalance.analytics.model.*;
import com.campusbalance.analytics.security.TokenService;
import com.campusbalance.analytics.service.CopingPlans;
import com.campusbalance.analytics.service.StudentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/**
 * Account + per-student endpoints. Every route with a {username} is restricted to that user
 * by @PreAuthorize — the logged-in user's token subject must match the URL.
 * Errors are thrown as ApiException and turned into status + message by ApiExceptionHandler.
 */
@RestController
@RequestMapping("/api")
public class StudentController {

    private static final String SELF_ONLY = "#username == authentication.name";

    @Autowired
    private StudentService service;

    @Autowired
    private TokenService tokenService;

    // ---------- Auth (public) ----------

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest credentials) {
        Student account = service.login(credentials.username(), credentials.password());
        if (account == null) {
            return ResponseEntity.status(401).body("Invalid Username or Password");
        }
        return ResponseEntity.ok(new LoginResponse(
                tokenService.issue(account), account.getUsername(), account.getName(), account.getRole()));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody AccountRequest request) {
        boolean created = service.registerStudent(request);
        if (created) {
            return ResponseEntity.ok("Success");
        } else {
            return ResponseEntity.status(409).body("That username is already taken.");
        }
    }

    @GetMapping("/admin-exists")
    public boolean adminExists() {
        return service.adminExists();
    }

    // The fixed department list, for the sign-up and staff dropdowns
    @GetMapping("/departments")
    public List<Departments.Department> departments() {
        return Departments.ALL;
    }

    // Changes the logged-in account's own password (any role)
    @PostMapping("/change-password")
    public String changePassword(Authentication authentication, @Valid @RequestBody ChangePasswordRequest request) {
        service.changePassword(authentication.getName(), request.currentPassword(), request.newPassword());
        return "Password changed";
    }

    // One-time setup route — only works until the first admin account is created, then always 409s
    @PostMapping("/register-admin")
    public ResponseEntity<?> registerAdmin(@Valid @RequestBody AccountRequest request) {
        boolean created = service.registerFirstAdmin(request);
        if (created) {
            return ResponseEntity.ok("Admin account created");
        } else {
            return ResponseEntity.status(409).body("An admin account already exists — this setup link is now locked.");
        }
    }

    // ---------- Dashboard / Insights ----------

    @PreAuthorize(SELF_ONLY)
    @GetMapping("/dashboard/{username}")
    public Map<String, Object> getDashboard(@PathVariable String username) {
        return service.getDashboardStats(username);
    }

    // Trend forecast + rule-based recommendations, powers the Insights tab
    @PreAuthorize(SELF_ONLY)
    @GetMapping("/insights/{username}")
    public Map<String, Object> getInsights(@PathVariable String username) {
        return service.getInsights(username);
    }

    // ---------- Semester lifecycle ----------

    // Body: { startDate, endDate } (yyyy-MM-dd). Rejects if a live active semester already exists.
    @PreAuthorize(SELF_ONLY)
    @PostMapping("/semesters/{username}")
    public Semester createSemester(@PathVariable String username, @RequestBody Semester request) {
        return service.createSemester(username, request);
    }

    // Full semester history (active + archived) — powers the Profile/History view
    @PreAuthorize(SELF_ONLY)
    @GetMapping("/semesters/{username}")
    public List<Semester> getSemesters(@PathVariable String username) {
        return service.getAllSemesters(username);
    }

    // Manually ends the active semester early (it also auto-ends on its own once its end date passes)
    @PreAuthorize(SELF_ONLY)
    @PostMapping("/end-semester/{username}")
    public String endSemester(@PathVariable String username) {
        service.endSemester(username);
        return "Semester ended — its data has moved to your Profile.";
    }

    // ---------- Subjects (scoped to the active semester) ----------

    @PreAuthorize(SELF_ONLY)
    @PostMapping("/subjects/{username}")
    public Subject addSubject(@PathVariable String username, @Valid @RequestBody Subject request) {
        return service.addSubject(username, request);
    }

    @PreAuthorize(SELF_ONLY)
    @GetMapping("/subjects/{username}")
    public List<Subject> getSubjects(@PathVariable String username) {
        return service.getSubjects(username);
    }

    // ---------- Focus / Recovery activities ----------

    // Body: { name, weight (1-10), custom }
    @PreAuthorize(SELF_ONLY)
    @PostMapping("/focus-activity/{username}")
    public String addFocusActivity(@PathVariable String username, @Valid @RequestBody Activity activity) {
        service.addFocusActivity(username, activity);
        return "Focus activity added";
    }

    @PreAuthorize(SELF_ONLY)
    @PostMapping("/recovery-activity/{username}")
    public String addRecoveryActivity(@PathVariable String username, @Valid @RequestBody Activity activity) {
        service.addRecoveryActivity(username, activity);
        return "Recovery activity added";
    }

    @PreAuthorize(SELF_ONLY)
    @PostMapping("/toggle-focus-activity/{username}")
    public String toggleFocusActivity(@PathVariable String username, @RequestParam String name) {
        service.toggleFocusActivity(username, name);
        return "Updated";
    }

    @PreAuthorize(SELF_ONLY)
    @PostMapping("/toggle-recovery-activity/{username}")
    public String toggleRecoveryActivity(@PathVariable String username, @RequestParam String name) {
        service.toggleRecoveryActivity(username, name);
        return "Updated";
    }

    // ---------- Wellness / Assignments ----------

    @PreAuthorize(SELF_ONLY)
    @PostMapping("/wellness/{username}")
    public String addWellness(@PathVariable String username, @Valid @RequestBody WellnessLog log) {
        service.addWellnessLog(username, log);
        return "Log Saved";
    }

    // Returns the created assignment, including its generated id
    @PreAuthorize(SELF_ONLY)
    @PostMapping("/assignments/{username}")
    public Student.Assignment addAssignment(@PathVariable String username, @Valid @RequestBody Student.Assignment a) {
        return service.addAssignment(username, a);
    }

    @PreAuthorize(SELF_ONLY)
    @PostMapping("/submit-task/{username}")
    public String submitTask(@PathVariable String username, @RequestParam String id) {
        service.submitAssignment(username, id);
        return "Assignment submitted";
    }

    // ---------- Interventions ----------

    // Body: { type: "SLEEP" | "STRESS" } — starts a 4-week Tier 2 coping plan
    @PreAuthorize(SELF_ONLY)
    @PostMapping("/coping-plan/{username}")
    public CopingPlans.PlanProgress startCopingPlan(@PathVariable String username, @RequestBody Map<String, String> body) {
        return service.startCopingPlan(username, body.get("type"));
    }

    @PreAuthorize(SELF_ONLY)
    @PostMapping("/end-coping-plan/{username}")
    public String endCopingPlan(@PathVariable String username) {
        service.endCopingPlan(username);
        return "Coping plan ended";
    }

    // Printable summary of recent data that the student can share with a counsellor
    @PreAuthorize(SELF_ONLY)
    @GetMapping("/report/{username}")
    public Map<String, Object> getSummaryReport(@PathVariable String username) {
        return service.getSummaryReport(username);
    }
}
