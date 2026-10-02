package org.tracker.gpatracker.accounts.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tracker.gpatracker.accounts.dto.AddCurrentCourseDTO;
import org.tracker.gpatracker.accounts.dto.CurrentCourseDTO;
import org.tracker.gpatracker.accounts.dto.GPACalculatorResponse;
import org.tracker.gpatracker.accounts.dto.PastCourseDTO;
import org.tracker.gpatracker.accounts.service.gpautils.GPACalc;
import org.tracker.gpatracker.accounts.service.gpautils.GPABuilder;
import org.tracker.gpatracker.accounts.service.gpautils.GradeDict;
import org.tracker.gpatracker.courses.model.Course;
import org.tracker.gpatracker.courses.model.PastCourse;
import org.tracker.gpatracker.assessmenttable.service.AssessmentTableService;
import org.tracker.gpatracker.courses.repository.CourseRepository;
import org.tracker.gpatracker.courses.repository.PastCourseRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class ManualUploadService {

    private static final Logger logger = LoggerFactory.getLogger(ManualUploadService.class);
    private final PastCourseRepository repo;
    private final CourseRepository courseRepo;
    private final StudentService studentService;
    private final AssessmentTableService assessmentTableService;
    private final GradeDict gradeDict;
    private final GPACalc gpaCalc;

    public ManualUploadService(PastCourseRepository repo, CourseRepository courseRepo,
                               StudentService studentService, AssessmentTableService assessmentTableService) {
        this.repo = repo;
        this.courseRepo = courseRepo;
        this.studentService = studentService;
        this.assessmentTableService = assessmentTableService;
        this.gradeDict = new GradeDict();
        this.gpaCalc = new GPACalc();
    }

    //This method performs two tasks
    //Calls the addCourse method
    //Calculates GPA Based off of list of PastCourseDTOs
    @Transactional
    public GPACalculatorResponse calculateGPA(List<PastCourseDTO> courseList) {
        logger.info("calculateGPA — {} courses submitted", courseList.size());
       studentService.clearPastCourses();


        //add new courses to the repo
        addPastCourses(courseList);


        List<PastCourse> pastCourses = repo.findByOwnerId(studentService.getStudentAccount().getId());
        List<GPABuilder> courseGPAList = new ArrayList<>();

        BigDecimal totalCredits = BigDecimal.ZERO;
        for (PastCourse course : pastCourses) {
            GPABuilder gpaBuilder = new GPABuilder();
            gpaBuilder.setCode(course.getName());
            gpaBuilder.setUnits(course.getUnits());
            totalCredits = totalCredits.add(new BigDecimal(course.getUnits()));
            gpaBuilder.setGrade(course.getGrade());
            courseGPAList.add(gpaBuilder);
        }

        BigDecimal result4 = gpaCalc.getGPA(courseGPAList, gradeDict.getStandardGradeDict());
        BigDecimal result12 = gpaCalc.getGPA(courseGPAList, gradeDict.getMacGradeDict());
        studentService.setGPA(result4, result12);

        logger.info("calculateGPA — GPA calculated: 4.0={}, 12.0={}", result4, result12);
        GPACalculatorResponse response = new GPACalculatorResponse();
        response.setGpa(result4);
        response.setTotalCredits(totalCredits);
        return response;
    }


    public void addPastCourses(List<PastCourseDTO> courseList) {
        for (PastCourseDTO courseDTO : courseList) {
            studentService.addPastCourse(courseDTO);
        }
    }

    public void addCurrentCourses(List<AddCurrentCourseDTO> courseList) {
        logger.info("addCurrentCourses — {} courses", courseList.size());
        for (AddCurrentCourseDTO courseDTO : courseList) {
            Optional<Course> course = courseRepo.findBycourseCode(courseDTO.getCourseCode());
            if (course.isEmpty()) {
                continue;
            }

            BigDecimal grade = null;
            if (courseDTO.getGrade() != null && !courseDTO.getGrade().isBlank()) {
                grade = new BigDecimal(courseDTO.getGrade());
            }
            studentService.addCurrentCourses(course.get(), grade);
            try {
                assessmentTableService.createFromSyllabus(courseDTO.getCourseCode());
            } catch (Exception e) { /* syllabus may not exist */ }
        }
    }

    public List<PastCourseDTO> getPastCourses() {
        return studentService.getPastCourses();
    }

    public List<CurrentCourseDTO> getPresentCourses(String term) {
        return studentService.getPresentCourses(term);
    }
}
