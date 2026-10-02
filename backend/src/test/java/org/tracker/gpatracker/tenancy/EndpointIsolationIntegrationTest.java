package org.tracker.gpatracker.tenancy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.repository.StudentRepo;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.repository.UserRepo;
import org.tracker.gpatracker.security.service.JWTService;
import org.tracker.gpatracker.syllabus.model.JobStatus;
import org.tracker.gpatracker.syllabus.model.SyllabusExtractionJob;
import org.tracker.gpatracker.syllabus.service.GeminiSyllabusExtractionService;

import java.time.LocalDateTime;
import java.util.UUID;

import org.tracker.gpatracker.support.ContainerIntegrationBase;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Isolation as seen from outside, through the real filter chain — so unlike the repository-level
 * tests this also exercises {@code JwtFilter} deriving the tenant from the signed token.
 *
 * <p>Scope note: this covers the syllabus-job endpoint, which is where the known IDOR was. The
 * remaining owned endpoints used to read from Mongo and could not be exercised here at all. Since
 * V16 they are ordinary filtered Postgres rows backed by the same Testcontainers database as every
 * other test, so extending this class to cover them is now possible and worth doing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EndpointIsolationIntegrationTest extends ContainerIntegrationBase {

    private static final String JOB_ID = "job-belonging-to-a";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JWTService jwtService;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private StudentRepo studentRepo;

    /** Mocked so the test drives the controller's ownership check directly, without a real job. */
    @MockitoBean
    private GeminiSyllabusExtractionService extractionService;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void seed() {
        UserContext.clear();
        SecurityContextHolder.clearContext();

        Login a = UserContext.callAsSystem(() -> createLogin("iso-a"));
        Login b = UserContext.callAsSystem(() -> createLogin("iso-b"));
        tokenA = a.token();
        tokenB = b.token();

        SyllabusExtractionJob job = new SyllabusExtractionJob();
        job.setId(JOB_ID);
        job.setOwnerId(a.studentId());
        job.setCourseCode("SFWRENG 2AA4");
        job.setTerm("Winter 2026");
        job.setStatus(JobStatus.DONE);

        when(extractionService.getJob(JOB_ID)).thenReturn(job);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        SecurityContextHolder.clearContext();
    }

    private record Login(Long studentId, String token) {
    }

    /**
     * Creates a verified user plus its student row and mints a real token for it.
     *
     * <p>Nothing here is rolled back, so each call takes a fresh identity rather than colliding with
     * the previous test method on the unique email constraint.
     */
    private Login createLogin(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Users user = new Users();
        user.setEmail(prefix + "-" + suffix + "@example.com");
        user.setUsername(prefix + "-" + suffix);
        user.setPassword("{noop}irrelevant");
        user.setEmailVerified(true);
        user.setLastLoginAt(LocalDateTime.now());
        Users saved = userRepo.save(user);

        Student student = new Student();
        student.setUser(saved);
        Long studentId = studentRepo.save(student).getId();

        return new Login(studentId, jwtService.generateToken(saved.getEmail(), saved.getId(), studentId));
    }

    /**
     * The known IDOR: this endpoint returned any job to any authenticated caller. The service is
     * mocked here, which is what makes this a test of the controller's own check rather than of the
     * owner filter underneath it — both now stand between B and A's job, and this asserts the outer
     * one independently.
     */
    @Test
    @DisplayName("B cannot read A's syllabus job")
    void jobStatusIsNotReadableAcrossTenants() throws Exception {
        mockMvc.perform(get("/api/syllabus/upload/" + JOB_ID)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A can still read A's own syllabus job")
    void ownerCanStillReadTheirJob() throws Exception {
        mockMvc.perform(get("/api/syllabus/upload/" + JOB_ID)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
    }

    /**
     * 404 rather than 403, so the response does not confirm that the id exists — otherwise the
     * endpoint still leaks which job ids are real.
     */
    @Test
    @DisplayName("a cross-tenant read is indistinguishable from a missing job")
    void crossTenantReadLooksLikeAMiss() throws Exception {
        when(extractionService.getJob("no-such-job")).thenReturn(null);

        mockMvc.perform(get("/api/syllabus/upload/no-such-job")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an unauthenticated request is still rejected")
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(get("/api/syllabus/upload/" + JOB_ID))
                .andExpect(status().isUnauthorized());
    }
}
