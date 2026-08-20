package org.tracker.gpatracker.accounts.service;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.tracker.gpatracker.accounts.dto.CurrentCourseDTO;
import org.tracker.gpatracker.accounts.dto.PastCourseDTO;
import org.tracker.gpatracker.accounts.event.GpaRecalculationEvent;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.repository.StudentRepo;
import org.tracker.gpatracker.courses.model.Course;
import org.tracker.gpatracker.courses.model.CourseEnrollement;
import org.tracker.gpatracker.courses.model.CourseEnrollementKey;
import org.tracker.gpatracker.courses.model.PastCourse;
import org.tracker.gpatracker.courses.repository.CourseEnrollementRepository;
import org.tracker.gpatracker.courses.repository.CourseRepository;
import org.tracker.gpatracker.courses.repository.PastCourseRepository;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.tenancy.UserContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class StudentService {
    private static final Logger logger = LoggerFactory.getLogger(StudentService.class);
    private static final int MAX_PAST_COURSES = 50;
    private static final int MAX_CURRENT_COURSES = 10;

    private final CourseEnrollementRepository enrollementRepo;
    private final StudentRepo studentRepo;
    private final PastCourseRepository pastCourseRepo;
    private final CourseRepository courseRepo;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${app.current-term}")
    private String currentTerm;

    public StudentService(CourseEnrollementRepository enrollementRepo, StudentRepo studentRepo,
                          PastCourseRepository pastCourseRepo, CourseRepository courseRepo,
                          ApplicationEventPublisher eventPublisher) {
        this.enrollementRepo = enrollementRepo;
        this.studentRepo = studentRepo;
        this.pastCourseRepo = pastCourseRepo;
        this.courseRepo = courseRepo;
        this.eventPublisher = eventPublisher;
    }
    public void createStudentAccount(Users user) {
        logger.info("createStudentAccount — userId: {}", user.getId());
        Student student = new Student();
        student.setUser(user);
        studentRepo.save(student);
    }

    /**
     * The current tenant's {@code Student}, resolved by primary key from the id bound to this
     * request.
     *
     * <p>Previously this read the SecurityContext and issued a {@code findByUserId} on every
     * call — several times per request in some flows. The id now arrives as a signed JWT claim,
     * so this is a single primary-key load.
     */
    public Student getStudentAccount() {
        Long studentId = getStudentID();
        return studentRepo.findById(studentId).orElseThrow(() -> new IllegalStateException(
                "No Student row for id " + studentId + " bound to this request"));
    }

    /** Look up a student id without a bound tenant. Used at login, before a token exists. */
    public Long findStudentIdByUserId(Long userId) {
        Student student = studentRepo.findByUserId(userId);
        return student == null ? null : student.getId();
    }

    public void addCurrentCourses(Course course, BigDecimal grade) {
        Student student = getStudentAccount();
        CourseEnrollementKey key = new CourseEnrollementKey(student.getId(), course.getId());

        //Avoid duplicate rows when the same transcript is uploaded multiple times
        if (enrollementRepo.existsById(key)) {
            return;
        }

        long currentCount = enrollementRepo.countByStudentsId(student.getId());
        if (currentCount >= MAX_CURRENT_COURSES) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Course limit reached"
            );
        }

        CourseEnrollement enrollement = new CourseEnrollement();
        enrollement.setId(key);
        enrollement.setStudents(student);
        enrollement.setCourses(course);
        enrollement.setGrade(grade);

        enrollementRepo.save(enrollement);
    }

    public void updateCourseGrade(String courseCode, BigDecimal grade) {
        Student student = getStudentAccount();
        Optional<Course> courseOpt = courseRepo.findBycourseCode(courseCode);
        if (courseOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found");
        }
        CourseEnrollementKey key = new CourseEnrollementKey(student.getId(), courseOpt.get().getId());
        CourseEnrollement enrollement = enrollementRepo.findByIdForUpdate(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Enrollment not found"));
        enrollement.setGrade(grade);
        enrollementRepo.save(enrollement);
        eventPublisher.publishEvent(new GpaRecalculationEvent(this, student.getId()));
    }

    public void toggleIncludeInGpa(String courseCode, boolean includeInGpa) {
        Student student = getStudentAccount();
        Optional<Course> courseOpt = courseRepo.findBycourseCode(courseCode);
        if (courseOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found");
        }
        CourseEnrollementKey key = new CourseEnrollementKey(student.getId(), courseOpt.get().getId());
        CourseEnrollement enrollement = enrollementRepo.findByIdForUpdate(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Enrollment not found"));
        enrollement.setIncludeInGpa(includeInGpa);
        enrollementRepo.save(enrollement);
        eventPublisher.publishEvent(new GpaRecalculationEvent(this, student.getId()));
    }


    public void setGPA(BigDecimal gpa4, BigDecimal gpa12) {
        Student student = getStudentAccount();
        student.setGpa4(gpa4);
        student.setGpa12(gpa12);
        studentRepo.save(student);
    }

    public void addPastCourse(PastCourseDTO courseDTO) {
        Student student = getStudentAccount();
        long currentCount = pastCourseRepo.countByOwnerId(student.getId());
        if (currentCount >= MAX_PAST_COURSES) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Course limit reached"
            );
        }
        PastCourse course = new PastCourse();
        // Owner is stamped from the request context on persist — see UserOwnedEntity.
        course.setName(courseDTO.getCourseName());
        course.setUnits(String.valueOf(courseDTO.getCredits()));
        course.setGrade(courseDTO.getGrade());
        pastCourseRepo.save(course);
        eventPublisher.publishEvent(new GpaRecalculationEvent(this, student.getId()));
    }

    //get past courses for the current student
    public List<PastCourseDTO> getPastCourses() {
        Student student = getStudentAccount();
        List<PastCourse> pastCourses = pastCourseRepo.findByOwnerId(student.getId());
        List<PastCourseDTO> courseDTOs = new ArrayList<>();

        for (PastCourse course : pastCourses) {
            PastCourseDTO dto = new PastCourseDTO();
            dto.setCourseName(course.getName());
            dto.setCredits(course.getUnits());
            dto.setGrade(course.getGrade());
            courseDTOs.add(dto);
        }
        return courseDTOs;
    }

    public void removePastCourseByNameAndCredits(String name, String credits) {
        Student student = getStudentAccount();
        pastCourseRepo.deleteByOwnerIdAndNameAndUnits(student.getId(), name, credits);
    }

    public void removePastCourseByName(String name) {
        Student student = getStudentAccount();
        pastCourseRepo.deleteByOwnerIdAndName(student.getId(), name);
    }

    public void deletePastCourse(String name) {
        logger.info("deletePastCourse — course: {}", name);
        Student student = getStudentAccount();
        try {
            pastCourseRepo.deleteByOwnerIdAndName(student.getId(), name);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Course already being deleted");
        }
        eventPublisher.publishEvent(new GpaRecalculationEvent(this, student.getId()));
    }

    public void clearPastCourses() {
        Student student = getStudentAccount();
        pastCourseRepo.deleteByOwnerId(student.getId());
        eventPublisher.publishEvent(new GpaRecalculationEvent(this, student.getId()));
    }

    public List<CurrentCourseDTO> getPresentCourses() {
        Student student = getStudentAccount();
        List<CourseEnrollement> enrollements = enrollementRepo.findByStudentsId(student.getId());
        List<CurrentCourseDTO> courseDTOs = new ArrayList<>();

        for (CourseEnrollement enrollement : enrollements) {
            Long courseId = enrollement.getId().getCourseId();
            Optional<Course> courseOpt = courseRepo.findById(courseId);
            if (courseOpt.isEmpty()) {
                continue;
            }

            Course course = courseOpt.get();
            CurrentCourseDTO dto = new CurrentCourseDTO();
            dto.setCourseCode(course.getCourseCode());
            dto.setCourseName(course.getCourseName());
            dto.setCredits(course.getCourseCredits() == null ? null : course.getCourseCredits().toString());
            dto.setGrade(enrollement.getGrade());
            dto.setTerm(currentTerm);
            dto.setIncludeInGpa(enrollement.isIncludeInGpa());
            courseDTOs.add(dto);
        }

        return courseDTOs;
    }


    public Long deleteCurrentCourse(String courseCode) {
        logger.info("deleteCurrentCourse — course: {}", courseCode);
        Student student = getStudentAccount();
        Optional<Course> courseOpt = courseRepo.findBycourseCode(courseCode);
        if (courseOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found");
        }
        CourseEnrollementKey key = new CourseEnrollementKey(student.getId(), courseOpt.get().getId());
        Optional<CourseEnrollement> enrollment = enrollementRepo.findById(key);
        if (enrollment.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Enrollment not found");
        }
        try {
            enrollementRepo.delete(enrollment.get());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Course already being deleted");
        }
        eventPublisher.publishEvent(new GpaRecalculationEvent(this, student.getId()));
        return student.getId();
    }

    public Student getStudentByUserId(Long userId){
        return studentRepo.findByUserId(userId);
    }

    /**
     * The tenant that owns rows for this request.
     *
     * <p>Read straight from {@link UserContext}, which {@code JwtFilter} populates from a signed
     * JWT claim. No database round-trip, and no way for a caller to supply a different id.
     */
    public Long getStudentID() {
        return UserContext.requireOwnerId();
    }
}
