package org.tracker.gpatracker.accounts.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.repository.StudentRepo;
import org.tracker.gpatracker.accounts.service.gpautils.GPABuilder;
import org.tracker.gpatracker.accounts.service.gpautils.GPACalc;
import org.tracker.gpatracker.accounts.service.gpautils.GradeDict;
import org.tracker.gpatracker.accounts.service.gpautils.NumericGradeConverter;
import org.tracker.gpatracker.courses.model.CourseEnrollement;
import org.tracker.gpatracker.courses.model.PastCourse;
import org.tracker.gpatracker.courses.repository.CourseEnrollementRepository;
import org.tracker.gpatracker.courses.repository.PastCourseRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class GpaRecalculationListener {

    private static final Logger logger = LoggerFactory.getLogger(GpaRecalculationListener.class);

    private final PastCourseRepository pastCourseRepo;
    private final CourseEnrollementRepository enrollementRepo;
    private final StudentRepo studentRepo;
    private final GradeDict gradeDict = new GradeDict();
    private final GPACalc gpaCalc = new GPACalc();

    public GpaRecalculationListener(PastCourseRepository pastCourseRepo,
                                     CourseEnrollementRepository enrollementRepo,
                                     StudentRepo studentRepo) {
        this.pastCourseRepo = pastCourseRepo;
        this.enrollementRepo = enrollementRepo;
        this.studentRepo = studentRepo;
    }

    @EventListener
    @Transactional
    public void onGpaRecalculation(GpaRecalculationEvent event) {
        Long studentId = event.getStudentId();
        logger.info("Recalculating GPA for student {}", studentId);

        Student student = studentRepo.findById(studentId).orElse(null);
        if (student == null) {
            logger.warn("Student {} not found, skipping GPA recalculation", studentId);
            return;
        }

        List<GPABuilder> allCourses = new ArrayList<>();

        // Past courses — already have letter grades and units
        List<PastCourse> pastCourses = pastCourseRepo.findByStudentId(studentId);
        for (PastCourse pc : pastCourses) {
            GPABuilder b = new GPABuilder();
            b.setCode(pc.getName());
            b.setUnits(pc.getUnits());
            b.setGrade(pc.getGrade());
            allCourses.add(b);
        }

        // Current courses — numeric grades need conversion to letters
        List<CourseEnrollement> currentCourses = enrollementRepo.findByStudentsIdAndIncludeInGpaTrue(studentId);
        for (CourseEnrollement ce : currentCourses) {
            if (ce.getGrade() == null || ce.getCourses() == null || ce.getCourses().getCourseCredits() == null) {
                continue;
            }

            String letterGrade = NumericGradeConverter.toLetter(ce.getGrade());

            GPABuilder b = new GPABuilder();
            b.setCode(ce.getCourses().getCourseCode());
            b.setUnits(ce.getCourses().getCourseCredits().toString());
            b.setGrade(letterGrade);
            allCourses.add(b);
        }

        BigDecimal gpa4 = gpaCalc.getGPA(allCourses, gradeDict.getStandardGradeDict());
        BigDecimal gpa12 = gpaCalc.getGPA(allCourses, gradeDict.getMacGradeDict());

        student.setGpa4(gpa4);
        student.setGpa12(gpa12);
        studentRepo.save(student);

        logger.info("Updated GPA for student {}: 4.0={}, 12.0={}", studentId, gpa4, gpa12);
    }
}
