package org.tracker.gpatracker.accounts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.repository.StudentRepo;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.courses.model.Course;
import org.tracker.gpatracker.courses.model.CourseEnrollement;
import org.tracker.gpatracker.courses.model.CourseEnrollementKey;
import org.tracker.gpatracker.courses.repository.CourseEnrollementRepository;
import org.tracker.gpatracker.courses.repository.CourseRepository;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.repository.UserRepo;
import org.tracker.gpatracker.support.ContainerIntegrationBase;
import org.tracker.gpatracker.tenancy.UserContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a syllabus re-upload does to the course it replaced the table for: the grade is cleared,
 * the GPA stops counting the course, and includeInGpa is left alone so the course counts again
 * as soon as a mark is entered.
 *
 * <p>Not {@code @Transactional}, for the same reason as {@code DeleteCurrentCourseIntegrationTest}:
 * the service opens its own transaction, and the GPA listener has to run inside it for real.
 */
@SpringBootTest
@ActiveProfiles("test")
class ClearCourseGradeIntegrationTest extends ContainerIntegrationBase {

    /** The test profile's current term. */
    private static final String TERM = "Winter 2026";

    @Autowired
    private UserRepo userRepo;
    @Autowired
    private StudentRepo studentRepo;
    @Autowired
    private CourseRepository courseRepo;
    @Autowired
    private CourseEnrollementRepository enrollementRepo;
    @Autowired
    private StudentService studentService;

    private Long userId;
    private Long studentId;
    private Course graded;
    private Course cleared;

    @BeforeEach
    void seed() {
        UserContext.clear();
        SecurityContextHolder.clearContext();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        UserContext.runAsSystem(() -> {
            Users user = new Users();
            user.setEmail("clear-" + suffix + "@example.com");
            user.setUsername("clear-" + suffix);
            user.setPassword("{noop}irrelevant");
            user.setEmailVerified(true);
            user.setLastLoginAt(LocalDateTime.now());
            Users saved = userRepo.save(user);

            Student student = new Student();
            student.setUser(saved);
            userId = saved.getId();
            studentId = studentRepo.save(student).getId();

            graded = courseRepo.save(new Course(null, "CLRA " + suffix, "Kept Course", 3L));
            cleared = courseRepo.save(new Course(null, "CLRB " + suffix, "Cleared Course", 3L));
        });

        UserContext.runAs(userId, studentId, () -> {
            enrol(graded, "90");
            enrol(cleared, "40");
        });
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        SecurityContextHolder.clearContext();
    }

    private void enrol(Course course, String grade) {
        CourseEnrollement e = new CourseEnrollement();
        e.setId(new CourseEnrollementKey(studentId, course.getId(), TERM));
        e.setStudents(studentRepo.findById(studentId).orElseThrow());
        e.setCourses(course);
        e.setGrade(new BigDecimal(grade));
        enrollementRepo.save(e);
    }

    private BigDecimal storedGpa12() {
        return UserContext.callAs(userId, studentId,
                () -> studentRepo.findById(studentId).orElseThrow().getGpa12());
    }

    @Test
    @DisplayName("clearing a grade drops the course from the GPA without excluding it")
    void clearedCourseLeavesTheGpaButStaysIncluded() {
        UserContext.runAs(userId, studentId, () -> studentService.updateCourseGrade(graded.getCourseCode(), TERM, new BigDecimal("90")));
        BigDecimal withBoth = storedGpa12();

        UserContext.runAs(userId, studentId, () -> studentService.clearCourseGrade(cleared.getCourseCode(), TERM));
        BigDecimal withoutCleared = storedGpa12();

        assertThat(withoutCleared)
                .as("the 40%% course no longer pulls the GPA down")
                .isGreaterThan(withBoth);

        UserContext.runAs(userId, studentId, () -> {
            CourseEnrollement e = enrollementRepo
                    .findById(new CourseEnrollementKey(studentId, cleared.getId(), TERM)).orElseThrow();
            assertThat(e.getGrade()).as("cleared, not set to 0").isNull();
            assertThat(e.isIncludeInGpa()).as("not toggled off").isTrue();
        });
    }

    @Test
    @DisplayName("clearing a course the student is not enrolled in is a no-op, not an error")
    void clearingAMissingEnrollmentDoesNothing() {
        UserContext.runAs(userId, studentId,
                () -> studentService.clearCourseGrade("NOSUCH 9Z99", TERM));
    }
}
