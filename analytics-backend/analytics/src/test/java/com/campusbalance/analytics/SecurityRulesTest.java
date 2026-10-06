package com.campusbalance.analytics;

import com.campusbalance.analytics.controller.AdminController;
import com.campusbalance.analytics.controller.FacultyController;
import com.campusbalance.analytics.controller.StudentController;
import com.campusbalance.analytics.model.Student;
import com.campusbalance.analytics.security.SecurityConfig;
import com.campusbalance.analytics.security.TokenService;
import com.campusbalance.analytics.service.AnalyticsService;
import com.campusbalance.analytics.service.StudentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may call what: tokens, roles, and "students only see their own data". Services are mocked. */
@WebMvcTest(controllers = {StudentController.class, FacultyController.class, AdminController.class})
@Import({SecurityConfig.class, TokenService.class})
class SecurityRulesTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TokenService tokenService;

    @MockitoBean
    private StudentService studentService;

    @MockitoBean
    private AnalyticsService analyticsService;

    private String tokenFor(String username, String role) {
        Student account = new Student();
        account.setUsername(username);
        account.setRole(role);
        return "Bearer " + tokenService.issue(account);
    }

    @Test
    void apiRequiresALoginToken() throws Exception {
        mvc.perform(get("/api/dashboard/alice")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/faculty/heatmap")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/create-faculty")).andExpect(status().isUnauthorized());
    }

    @Test
    void forgedTokenIsRejected() throws Exception {
        String forged = tokenFor("alice", "STUDENT").replaceAll(".{4}$", "abcd");
        mvc.perform(get("/api/dashboard/alice").header("Authorization", forged)).andExpect(status().isUnauthorized());
    }

    @Test
    void studentCanOnlyReachTheirOwnData() throws Exception {
        String alice = tokenFor("alice", "STUDENT");
        mvc.perform(get("/api/dashboard/alice").header("Authorization", alice)).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard/bob").header("Authorization", alice)).andExpect(status().isForbidden());
        mvc.perform(post("/api/submit-task/bob").param("id", "x").header("Authorization", alice)).andExpect(status().isForbidden());
    }

    @Test
    void staffPagesNeedTheRightRole() throws Exception {
        String student = tokenFor("alice", "STUDENT");
        String faculty = tokenFor("fran", "FACULTY");
        String admin = tokenFor("ada", "ADMIN");

        mvc.perform(get("/api/faculty/heatmap").header("Authorization", student)).andExpect(status().isForbidden());
        mvc.perform(get("/api/faculty/heatmap").header("Authorization", faculty)).andExpect(status().isOk());
        mvc.perform(get("/api/faculty/heatmap").header("Authorization", admin)).andExpect(status().isOk());

        mvc.perform(get("/api/admin/trends").header("Authorization", faculty)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/trends").header("Authorization", admin)).andExpect(status().isOk());
    }

    @Test
    void changePasswordNeedsLoginAndAppliesToTheTokenOwner() throws Exception {
        String body = "{\"currentPassword\":\"old-pass-1\",\"newPassword\":\"new-pass-123\"}";
        mvc.perform(post("/api/change-password").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/change-password").header("Authorization", tokenFor("fran", "FACULTY"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(studentService).changePassword("fran", "old-pass-1", "new-pass-123");
    }

    @Test
    void loginAndSignupArePublic() throws Exception {
        mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Invalid Username or Password"));

        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"short\",\"name\":\"Alice\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Password must be at least 8 characters"));
    }
}
