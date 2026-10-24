package org.tracker.gpatracker.assessmenttable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.repository.StudentRepo;
import org.tracker.gpatracker.assessmenttable.model.AssessmentScheme;
import org.tracker.gpatracker.assessmenttable.model.AssessmentTableDocument;
import org.tracker.gpatracker.assessmenttable.model.SchemeAssessment;
import org.tracker.gpatracker.assessmenttable.repository.AssessmentTableDocumentRepository;
import org.tracker.gpatracker.leaderboard.service.CourseScoring;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.repository.UserRepo;
import org.tracker.gpatracker.security.service.JWTService;
import org.tracker.gpatracker.support.ContainerIntegrationBase;
import org.tracker.gpatracker.tenancy.UserContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A full-year course, end to end: first half graded in Fall, second half in Winter, and the course
 * scoring on the leaderboard in the season it finishes.
 *
 * <p>Runs through the real endpoints and the real jsonb converter, because the thing under test is
 * whether last term's grade timestamps survive the trip into this term's table. A mocked repository
 * would not exercise the converter that stores them.
 *
 * <p>The current term is Winter 2026 (test profile) and the first half is Fall 2025. Due dates are
 * relative to today rather than inside those terms, because {@code CourseScoring} checks grades
 * against the real clock: the Fall row is long past with a mark entered on time, and the Winter
 * row is due today so a mark entered now is inside its window.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MultiTermCarryForwardIntegrationTest extends ContainerIntegrationBase {

    private static final String FULL_YEAR = "ARTSSCI 1D06 A/B";
    private static final String ORDINARY = "COMPSCI 2C03";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JWTService jwtService;
    @Autowired
    private UserRepo userRepo;
    @Autowired
    private StudentRepo studentRepo;
    @Autowired
    private AssessmentTableDocumentRepository tables;

    private Long userId;
    private Long studentId;
    private String token;

    private final LocalDate essayDue = LocalDate.now(ZoneOffset.UTC).minusDays(120);
    private final Instant essayGraded = essayDue.atStartOfDay(ZoneOffset.UTC).toInstant().plus(2, ChronoUnit.DAYS)
            .plus(15, ChronoUnit.HOURS);
    private final LocalDate examDue = LocalDate.now(ZoneOffset.UTC);

    @BeforeEach
    void seed() {
        UserContext.clear();
        SecurityContextHolder.clearContext();
        UserContext.runAsSystem(this::createLogin);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void createLogin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Users user = new Users();
        user.setEmail("fullyear-" + suffix + "@example.com");
        user.setUsername("fullyear-" + suffix);
        user.setPassword("{noop}irrelevant");
        user.setEmailVerified(true);
        user.setLastLoginAt(LocalDateTime.now());
        Users saved = userRepo.save(user);

        Student student = new Student();
        student.setUser(saved);
        userId = saved.getId();
        studentId = studentRepo.save(student).getId();
        token = jwtService.generateToken(saved.getEmail(), userId, studentId);
    }

    /** Last term's table: the essay graded inside its window, the final exam not yet written. */
    private void seedFallHalf(String courseCode) {
        SchemeAssessment essay = new SchemeAssessment();
        essay.setName("Fall Essay");
        essay.setDueDate(essayDue.toString());
        essay.setWeight(new BigDecimal("40"));
        essay.setGrade(new BigDecimal("85"));
        essay.setFirstGradedAt(essayGraded);
        essay.setLastGradedAt(essayGraded);

        SchemeAssessment exam = new SchemeAssessment();
        exam.setName("Final Exam");
        exam.setDueDate(examDue.toString());
        exam.setWeight(new BigDecimal("60"));

        AssessmentScheme scheme = new AssessmentScheme();
        scheme.setSchemeName("Marking Scheme 1");
        scheme.setAssessments(List.of(essay, exam));

        AssessmentTableDocument fall = new AssessmentTableDocument();
        fall.setOwnerId(studentId);
        fall.setCourseCode(courseCode);
        fall.setTerm("Fall 2025");
        fall.setSchemes(List.of(scheme));
        UserContext.runAs(userId, studentId, () -> tables.save(fall));
    }

    /** What the client sends after the student enters the final exam mark this term. */
    private String winterSave(String courseCode) {
        return """
                {"courseCode": "%s", "term": "Winter 2026", "schemes": [{
                  "schemeName": "Marking Scheme 1",
                  "assessments": [
                    {"name": "Fall Essay", "dueDate": "%s", "weight": 40, "grade": 85},
                    {"name": "Final Exam", "dueDate": "%s", "weight": 60, "grade": 90}
                  ]}]}
                """.formatted(courseCode, essayDue, examDue);
    }

    private AssessmentTableDocument storedWinterTable(String courseCode) {
        Optional<AssessmentTableDocument> stored = UserContext.callAs(userId, studentId,
                () -> tables.findByOwnerIdAndCourseCodeAndTerm(studentId, courseCode, "Winter 2026"));
        assertThat(stored).as("a Winter 2026 table was written").isPresent();
        return stored.get();
    }

    private static SchemeAssessment row(AssessmentTableDocument table, String name) {
        return table.getSchemes().get(0).getAssessments().stream()
                .filter(r -> name.equals(r.getName()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("the second term opens on the first half's table, marks included")
    void secondTermShowsTheFirstHalf() throws Exception {
        seedFallHalf(FULL_YEAR);

        mockMvc.perform(get("/api/assessment-table/get-assessment-table")
                        .param("courseCode", FULL_YEAR)
                        .param("term", "Winter 2026")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['Marking Scheme 1'][0].assessmentName").value("Fall Essay"))
                .andExpect(jsonPath("$['Marking Scheme 1'][0].grade").value(85));
    }

    @Test
    @DisplayName("a full-year course keeps its on-time fall mark and scores in the winter season")
    void fullYearCourseScoresWhenItFinishes() throws Exception {
        seedFallHalf(FULL_YEAR);

        mockMvc.perform(post("/api/assessment-table/save")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(winterSave(FULL_YEAR)))
                .andExpect(status().isOk());

        AssessmentTableDocument winter = storedWinterTable(FULL_YEAR);
        assertThat(row(winter, "Fall Essay").getFirstGradedAt())
                .as("the fall mark keeps the stamp from when it was entered last term")
                .isEqualTo(essayGraded);

        // 0.4 x 85 + 0.6 x 90 = 88. Both marks are inside their windows, so the course counts.
        assertThat(CourseScoring.percentage(winter.getSchemes(), Instant.now()))
                .hasValueSatisfying(pct -> assertThat(pct.doubleValue()).isCloseTo(88.0, within(1e-9)));
    }

    @Test
    @DisplayName("an ordinary course does not inherit last term's stamps, so a backfilled mark scores nothing")
    void ordinaryCourseDoesNotCarryForward() throws Exception {
        seedFallHalf(ORDINARY);

        mockMvc.perform(post("/api/assessment-table/save")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(winterSave(ORDINARY)))
                .andExpect(status().isOk());

        AssessmentTableDocument winter = storedWinterTable(ORDINARY);
        assertThat(row(winter, "Fall Essay").getFirstGradedAt()).isAfter(essayGraded);
        assertThat(CourseScoring.percentage(winter.getSchemes(), Instant.now())).isEmpty();
    }
}
