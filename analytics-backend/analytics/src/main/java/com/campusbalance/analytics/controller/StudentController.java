package com.campusbalance.analytics.controller;

import com.campusbalance.analytics.model.*;
import com.campusbalance.analytics.service.StudentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class StudentController {

    @Autowired
    private StudentService service;

    @GetMapping("/dashboard/{username}")
    public Map<String, Object> getDashboard(@PathVariable String username) {
        return service.getDashboardStats(username);
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Student s) {
        boolean created = service.registerStudent(s);
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

    // One-time setup route — only works until the first admin account is created, then always 409s
    @PostMapping("/register-admin")
    public ResponseEntity<?> registerAdmin(@RequestBody Student s) {
        boolean created = service.registerFirstAdmin(s);
        if (created) {
            return ResponseEntity.ok("Admin account created");
        } else {
            return ResponseEntity.status(409).body("An admin account already exists — this setup link is now locked.");
        }
    }

    // ---------- Semester lifecycle ----------

    // Body: { startDate, endDate } (yyyy-MM-dd). Rejects if a live active semester already exists.
    @PostMapping("/semesters/{username}")
    public ResponseEntity<?> createSemester(@PathVariable String username, @RequestBody Semester request) {
        Map<String, Object> result = service.createSemester(username, request);
        if (result.containsKey("error")) {
            return ResponseEntity.status(409).body(result.get("error"));
        }
        return ResponseEntity.ok(result.get("semester"));
    }

    // Full semester history (active + archived) — powers the Profile/History view
    @GetMapping("/semesters/{username}")
    public List<Semester> getSemesters(@PathVariable String username) {
        return service.getAllSemesters(username);
    }

    // Manually ends the active semester early (it also auto-ends on its own once its end date passes)
    @PostMapping("/end-semester/{username}")
    public ResponseEntity<?> endSemester(@PathVariable String username) {
        boolean ended = service.endSemester(username);
        if (ended) {
            return ResponseEntity.ok("Semester ended — its data has moved to your Profile.");
        } else {
            return ResponseEntity.status(409).body("No active semester to end.");
        }
    }

    // ---------- Subjects (scoped to the active semester) ----------

    @PostMapping("/subjects/{username}")
    public ResponseEntity<?> addSubject(@PathVariable String username, @RequestBody Subject request) {
        Map<String, Object> result = service.addSubject(username, request);
        if (result.containsKey("error")) {
            return ResponseEntity.status(409).body(result.get("error"));
        }
        return ResponseEntity.ok(result.get("subject"));
    }

    @GetMapping("/subjects/{username}")
    public List<Subject> getSubjects(@PathVariable String username) {
        return service.getSubjects(username);
    }

    // ---------- Focus / Recovery activities ----------

    // Body: { name, weight (1-10), custom }
    @PostMapping("/focus-activity/{username}")
    public ResponseEntity<?> addFocusActivity(@PathVariable String username, @RequestBody Activity activity) {
        boolean ok = service.addFocusActivity(username, activity);
        if (ok) return ResponseEntity.ok("Focus activity added");
        return ResponseEntity.status(409).body("No active semester. Start one from the Semester tab first.");
    }

    @PostMapping("/recovery-activity/{username}")
    public ResponseEntity<?> addRecoveryActivity(@PathVariable String username, @RequestBody Activity activity) {
        boolean ok = service.addRecoveryActivity(username, activity);
        if (ok) return ResponseEntity.ok("Recovery activity added");
        return ResponseEntity.status(409).body("No active semester. Start one from the Semester tab first.");
    }

    @PostMapping("/toggle-focus-activity/{username}")
    public void toggleFocusActivity(@PathVariable String username, @RequestParam String name) {
        service.toggleFocusActivity(username, name);
    }

    @PostMapping("/toggle-recovery-activity/{username}")
    public void toggleRecoveryActivity(@PathVariable String username, @RequestParam String name) {
        service.toggleRecoveryActivity(username, name);
    }

    // ---------- Wellness / Assignments ----------

    @PostMapping("/wellness/{username}")
    public ResponseEntity<?> addWellness(@PathVariable String username, @RequestBody WellnessLog log) {
        boolean saved = service.addWellnessLog(username, log);
        if (saved) {
            return ResponseEntity.ok("Log Saved");
        } else {
            return ResponseEntity.status(409).body("You've already logged today, or you have no active semester.");
        }
    }

    @PostMapping("/assignments/{username}")
    public ResponseEntity<?> addAssignment(@PathVariable String username, @RequestBody Student.Assignment a) {
        boolean saved = service.addAssignment(username, a);
        if (saved) {
            return ResponseEntity.ok("Assignment Added");
        } else {
            return ResponseEntity.status(409).body("No active semester. Start one from the Semester tab first.");
        }
    }

    @PostMapping("/submit-task/{username}")
    public void submitTask(@PathVariable String username, @RequestParam String title) {
        service.submitAssignment(username, title);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        Student student = service.login(username, password);
        if (student != null) {
            return ResponseEntity.ok(student);
        } else {
            return ResponseEntity.status(401).body("Invalid Username or Password");
        }
    }
}