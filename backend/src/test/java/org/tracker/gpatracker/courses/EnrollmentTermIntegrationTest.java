package org.tracker.gpatracker.courses;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.repository.StudentRepo;
import org.tracker.gpatracker.courses.model.Course;
import org.tracker.gpatracker.courses.model.CourseEnrollement;
import org.tracker.gpatracker.courses.model.CourseEnrollementKey;
import org.tracker.gpatracker.courses.repository.CourseEnrollementRepository;
import org.tracker.gpatracker.courses.repository.CourseRepository;
import org.tracker.gpatracker.support.ContainerIntegrationBase;
import org.tracker.gpatracker.tenancy.UserContext;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test the whole Postgres change exists for: one student, one course, two terms, two rows.
 *
 * <p>Before the term joined the primary key this was impossible to express -- the second save
 * was an update of the first.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EnrollmentTermIntegrationTest extends ContainerIntegrationBase {

    private static final String CURRENT = "Winter 2026";
    private static final String PAST = "Fall 2025";

    @Autowired
    private EntityManager em;

    @Autowired
    private StudentRepo studentRepo;

    @Autowired
    private CourseRepository courseRepo;

    @Autowired
    private CourseEnrollementRepository enrollementRepo;

    private Long studentA;
    private Long studentB;
    private Course course;

    @BeforeEach
    void seed() {
        studentA = UserContext.callAsSystem(() -> studentRepo.save(new Student()).getId());
        studentB = UserContext.callAsSystem(() -> studentRepo.save(new Student()).getId());
        course = UserContext.callAsSystem(() -> {
            Course c = new Course();
            c.setCourseCode("COMPSCI 1MD3");
            c.setCourseName("Introduction to Programming");
            c.setCourseCredits(3L);
            return courseRepo.save(c);
        });

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private CourseEnrollement enrol(Long studentId, String term, String grade) {
        CourseEnrollement e = new CourseEnrollement();
        e.setId(new CourseEnrollementKey(studentId, course.getId(), term));
        e.setStudents(em.getReference(Student.class, studentId));
        e.setCourses(em.getReference(Course.class, course.getId()));
        e.setGrade(new BigDecimal(grade));
        return enrollementRepo.save(e);
    }

    @Test
    @DisplayName("the same course in two terms is two rows, each with its own grade")
    void sameCourseTwoTerms() {
        UserContext.runAs(null, studentA, () -> {
            enrol(studentA, CURRENT, "91.0");
            enrol(studentA, PAST, "78.5");
            em.flush();
            em.clear();

            List<CourseEnrollement> all = enrollementRepo.findByStudentsId(studentA);
            assertThat(all)
                    .as("two terms means two rows; before the key change the second overwrote the first")
                    .hasSize(2);

            assertThat(enrollementRepo.findByStudentsIdAndIdTerm(studentA, CURRENT))
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.getTerm()).isEqualTo(CURRENT);
                        assertThat(e.getGrade()).isEqualByComparingTo("91.0");
                    });

            assertThat(enrollementRepo.findByStudentsIdAndIdTerm(studentA, PAST))
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.getTerm()).isEqualTo(PAST);
                        assertThat(e.getGrade())
                                .as("the past term's grade must not have been overwritten")
                                .isEqualByComparingTo("78.5");
                    });
        });
    }

    @Test
    @DisplayName("findById resolves the term, not just the student and course")
    void findByIdIsTermScoped() {
        UserContext.runAs(null, studentA, () -> {
            enrol(studentA, CURRENT, "91.0");
            enrol(studentA, PAST, "78.5");
            em.flush();
            em.clear();

            assertThat(enrollementRepo.findById(new CourseEnrollementKey(studentA, course.getId(), PAST)))
                    .get()
                    .satisfies(e -> assertThat(e.getGrade()).isEqualByComparingTo("78.5"));

            assertThat(enrollementRepo.findById(
                    new CourseEnrollementKey(studentA, course.getId(), "Winter 3025")))
                    .as("a term the student does not have resolves to nothing")
                    .isEmpty();
        });
    }

    @Test
    @DisplayName("the course cap counts one term, not every term")
    void courseCapIsPerTerm() {
        UserContext.runAs(null, studentA, () -> {
            enrol(studentA, PAST, "78.5");
            enrol(studentA, CURRENT, "91.0");
            em.flush();
            em.clear();

            assertThat(enrollementRepo.countByStudentsIdAndIdTerm(studentA, CURRENT))
                    .as("counting every term would lock a returning student out of their second term")
                    .isEqualTo(1);
            assertThat(enrollementRepo.findByStudentsId(studentA))
                    .as("both terms are still there; only the count the cap reads is scoped")
                    .hasSize(2);
        });
    }

    @Test
    @DisplayName("the term is a new dimension on an owned table, and does not weaken isolation")
    void termDoesNotLeakAcrossStudents() {
        UserContext.runAs(null, studentA, () -> {
            enrol(studentA, PAST, "78.5");
            em.flush();
        });
        UserContext.runAs(null, studentB, () -> {
            enrol(studentB, PAST, "55.0");
            em.flush();
        });
        em.clear();

        UserContext.runAs(null, studentA, () -> {
            assertThat(enrollementRepo.findByStudentsIdAndIdTerm(studentA, PAST))
                    .singleElement()
                    .satisfies(e -> assertThat(e.getGrade()).isEqualByComparingTo("78.5"));

            assertThat(enrollementRepo.findById(new CourseEnrollementKey(studentB, course.getId(), PAST)))
                    .as("student B's Fall 2025 row must be unreachable as student A")
                    .isEmpty();
        });
    }
}
