package org.tracker.gpatracker.accounts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.repository.StudentRepo;
import org.tracker.gpatracker.assessmenttable.model.AssessmentScheme;
import org.tracker.gpatracker.assessmenttable.model.AssessmentTableDocument;
import org.tracker.gpatracker.assessmenttable.repository.AssessmentTableDocumentRepository;
import org.tracker.gpatracker.courses.model.Course;
import org.tracker.gpatracker.courses.model.CourseEnrollement;
import org.tracker.gpatracker.courses.model.CourseEnrollementKey;
import org.tracker.gpatracker.courses.repository.CourseEnrollementRepository;
import org.tracker.gpatracker.courses.repository.CourseRepository;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.repository.UserRepo;
import org.tracker.gpatracker.security.service.JWTService;
import org.tracker.gpatracker.support.ContainerIntegrationBase;
import org.tracker.gpatracker.tenancy.UserContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deleting a current course removes its enrollment and its assessment table in one request.
 *
 * <p>Deliberately not {@code @Transactional}. The bug this guards against was a derived
 * {@code deleteBy} query reached with no transaction open, which a test-managed transaction would
 * have supplied and so hidden. The request has to run exactly as it does in production: committed
 * rows, and whatever transactions the application opens for itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeleteCurrentCourseIntegrationTest extends ContainerIntegrationBase {

    /** The test profile's current term, the only one a delete is allowed against. */
    private static final String TERM = "Winter 2026";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JWTService jwtService;
    @Autowired
    private UserRepo userRepo;
    @Autowired
    private StudentRepo studentRepo;
    @Autowired
    private CourseRepository courseRepo;
    @Autowired
    private CourseEnrollementRepository enrollementRepo;
    @Autowired
    private AssessmentTableDocumentRepository tables;

    private Long userId;
    private Long studentId;
    private String token;
    private Course course;

    @BeforeEach
    void seed() {
        UserContext.clear();
        SecurityContextHolder.clearContext();

        // Rows are committed and never rolled back, so every identity is unique to this run.
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        UserContext.runAsSystem(() -> {
            Users user = new Users();
            user.setEmail("delete-" + suffix + "@example.com");
            user.setUsername("delete-" + suffix);
            user.setPassword("{noop}irrelevant");
            user.setEmailVerified(true);
            user.setLastLoginAt(LocalDateTime.now());
            Users saved = userRepo.save(user);

            Student student = new Student();
            student.setUser(saved);
            Student savedStudent = studentRepo.save(student);
            userId = saved.getId();
            studentId = savedStudent.getId();
            token = jwtService.generateToken(saved.getEmail(), userId, studentId);

            Course c = new Course();
            c.setCourseCode("DELTEST " + suffix);
            c.setCourseName("Delete Test Course");
            c.setCourseCredits(3L);
            course = courseRepo.save(c);
        });

        UserContext.runAs(userId, studentId, () -> {
            CourseEnrollement e = new CourseEnrollement();
            e.setId(new CourseEnrollementKey(studentId, course.getId(), TERM));
            e.setStudents(studentRepo.findById(studentId).orElseThrow());
            e.setCourses(course);
            enrollementRepo.save(e);

            AssessmentScheme scheme = new AssessmentScheme();
            scheme.setSchemeName("Marking Scheme 1");
            scheme.setAssessments(List.of());

            AssessmentTableDocument table = new AssessmentTableDocument();
            table.setOwnerId(studentId);
            table.setCourseCode(course.getCourseCode());
            table.setTerm(TERM);
            table.setSchemes(List.of(scheme));
            tables.save(table);
        });
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("deleting a course succeeds and removes both its enrollment and its assessment table")
    void deleteRemovesEnrollmentAndTable() throws Exception {
        mockMvc.perform(delete("/api/my-courses/delete-course")
                        .param("courseCode", course.getCourseCode())
                        .param("term", TERM)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        UserContext.runAs(userId, studentId, () -> {
            assertThat(enrollementRepo.findById(new CourseEnrollementKey(studentId, course.getId(), TERM)))
                    .as("the enrollment is gone")
                    .isEmpty();
            assertThat(tables.findByOwnerIdAndCourseCodeAndTerm(studentId, course.getCourseCode(), TERM))
                    .as("the assessment table is gone too, not left orphaned behind a 500")
                    .isEmpty();
        });
    }
}
