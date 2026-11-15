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
import org.tracker.gpatracker.courses.model.PastCourse;
import org.tracker.gpatracker.courses.repository.PastCourseRepository;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.repository.UserRepo;
import org.tracker.gpatracker.security.service.JWTService;
import org.tracker.gpatracker.support.ContainerIntegrationBase;
import org.tracker.gpatracker.tenancy.UserContext;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Clearing the GPA calculator removes every past course the caller has in one request, and none
 * of anyone else's.
 *
 * <p>Not {@code @Transactional}, for the same reason as {@link DeleteCurrentCourseIntegrationTest}:
 * a test-managed transaction would cover for a derived {@code deleteBy} reached without one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeleteAllPastCoursesIntegrationTest extends ContainerIntegrationBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JWTService jwtService;
    @Autowired
    private UserRepo userRepo;
    @Autowired
    private StudentRepo studentRepo;
    @Autowired
    private PastCourseRepository pastCourseRepo;

    private long[] caller;
    private long[] other;
    private String token;

    @BeforeEach
    void seed() {
        UserContext.clear();
        SecurityContextHolder.clearContext();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        caller = createStudent("clear-a-" + suffix);
        other = createStudent("clear-b-" + suffix);
        token = jwtService.generateToken("clear-a-" + suffix + "@example.com", caller[0], caller[1]);

        UserContext.runAs(caller[0], caller[1], () -> {
            for (int i = 0; i < 25; i++) {
                savePastCourse("COURSE " + i);
            }
        });
        UserContext.runAs(other[0], other[1], () -> savePastCourse("OTHER 1"));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("delete all removes every one of the caller's past courses and leaves other students' alone")
    void deleteAllClearsOnlyTheCaller() throws Exception {
        mockMvc.perform(delete("/api/my-courses/past-courses/all")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        UserContext.runAs(caller[0], caller[1], () ->
                assertThat(pastCourseRepo.countByOwnerId(caller[1]))
                        .as("all 25 of the caller's courses are gone")
                        .isZero());
        UserContext.runAs(other[0], other[1], () ->
                assertThat(pastCourseRepo.countByOwnerId(other[1]))
                        .as("the other student's course is untouched")
                        .isEqualTo(1));
    }

    /** Returns {userId, studentId}. */
    private long[] createStudent(String handle) {
        long[] ids = new long[2];
        UserContext.runAsSystem(() -> {
            Users user = new Users();
            user.setEmail(handle + "@example.com");
            user.setUsername(handle);
            user.setPassword("{noop}irrelevant");
            user.setEmailVerified(true);
            user.setLastLoginAt(LocalDateTime.now());
            Users saved = userRepo.save(user);

            Student student = new Student();
            student.setUser(saved);
            Student savedStudent = studentRepo.save(student);
            ids[0] = saved.getId();
            ids[1] = savedStudent.getId();
        });
        return ids;
    }

    private void savePastCourse(String name) {
        PastCourse course = new PastCourse();
        course.setName(name);
        course.setGrade("A");
        course.setUnits("3.0");
        pastCourseRepo.save(course);
    }
}
