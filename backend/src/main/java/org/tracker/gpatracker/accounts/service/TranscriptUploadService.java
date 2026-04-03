package org.tracker.gpatracker.accounts.service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.tracker.gpatracker.accounts.dto.GPACalculatorResponse;
import org.tracker.gpatracker.accounts.dto.PastCourseDTO;
import org.tracker.gpatracker.accounts.service.gpautils.*;
import org.tracker.gpatracker.assessmenttable.service.AssessmentTableService;
import org.tracker.gpatracker.courses.model.Course;
import org.tracker.gpatracker.courses.repository.CourseRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

@Service
public class TranscriptUploadService {
    private static final Logger logger = LoggerFactory.getLogger(TranscriptUploadService.class);
    private final GradeFinder gradeFinder;
    private final CodeFinder codeFinder;
    private final GradeDict gradeDict;
    private final TermFinder termFinder;
    private final GPACalc gpaCalc;
    private final UnitFinder unitFinder;
    private final String currentTerm;

    private final CourseRepository repo;
    private final StudentService studentService;
    private final AssessmentTableService assessmentTableService;

    public TranscriptUploadService(CourseRepository repo, StudentService studentService,
                                   AssessmentTableService assessmentTableService) {
        this.repo = repo;
        this.studentService = studentService;
        this.assessmentTableService = assessmentTableService;
        gradeFinder = new GradeFinder();
        codeFinder = new CodeFinder();
        gradeDict = new GradeDict();
        gpaCalc = new GPACalc();
        unitFinder = new UnitFinder();
        termFinder = new TermFinder();
        currentTerm = "--- 2026 Winter ---";
    }

    public String[] convertToString(MultipartFile file) throws IOException {
        return TextConverter.convertPDFtoTxt(file);
    }

    @Transactional
    public GPACalculatorResponse processTranscriptForGPACalc(MultipartFile file) throws IOException {
        logger.info("processTranscriptForGPACalc — parsing PDF");
        String[] line = convertToString(file);

        //clear previous past courses and credits once before processing
        studentService.clearPastCourses();

        List<GPABuilder> courseList = new ArrayList<>();
        BigDecimal totalCredits = BigDecimal.ZERO;
        boolean termFound = false;

        for (int i = 0; i < line.length; i++) {
            String currentLine = line[i];
            if (termFinder.containsTerm(currentLine)) {
                termFound = termFinder.containsCurrentTerm(currentLine, currentTerm);
            }

            if (codeFinder.containsCourse(currentLine)) {
                String code = codeFinder.normalizeMultiYearCourse(codeFinder.getCourse(currentLine));
                if (termFound) {
                    enrollCurrentTermCourse(code);
                }
                totalCredits = recordPastCourse(code, line, i, courseList, totalCredits);
            }
        }

        if (courseList.isEmpty()) {
            throw new IllegalStateException("No course/grade/units pairs found in uploaded file.");
        }

        logger.info("processTranscriptForGPACalc — {} courses extracted", courseList.size());
        BigDecimal result4 = gpaCalc.getGPA(courseList, gradeDict.getStandardGradeDict());
        BigDecimal result12 = gpaCalc.getGPA(courseList, gradeDict.getMacGradeDict());
        studentService.setGPA(result4, result12);
        GPACalculatorResponse response = new GPACalculatorResponse();
        response.setGpa(result4);
        response.setTotalCredits(totalCredits);
        return response;
    }

    private void enrollCurrentTermCourse(String code) {
        Optional<Course> course = repo.findBycourseCode(code);
        if (course.isPresent()) {
            studentService.addCurrentCourses(course.get(), null);
            try {
                assessmentTableService.createFromSyllabus(code);
            } catch (Exception e) { /* syllabus may not exist */ }
        }
    }

    private BigDecimal recordPastCourse(String code, String[] line, int startIndex,
                                        List<GPABuilder> courseList, BigDecimal totalCredits) {
        String units = null;
        String grade = null;

        // search forward until the next course code or term header
        for (int j = startIndex; j < line.length; j++) {
            if (j > startIndex && (codeFinder.containsCourse(line[j]) || termFinder.containsTerm(line[j]))) {
                break;
            }
            String candidate = line[j];
            if (units == null && unitFinder.containsUnits(candidate)) {
                units = unitFinder.getUnits(candidate);
            }
            if (grade == null && gradeFinder.containsGrade(candidate)) {
                grade = gradeFinder.getGrade(candidate);
            }
            if (units != null && grade != null) {
                break;
            }
        }

        if (units == null || grade == null) return totalCredits;

        // If a course with the same code already exists, remove it
        // (McMaster retake policy: only the latest attempt counts towards GPA)
        Iterator<GPABuilder> it = courseList.iterator();
        while (it.hasNext()) {
            GPABuilder existing = it.next();
            if (existing.getCode().equals(code)) {
                it.remove();
            }
        }
        studentService.removePastCourseByName(code);

        GPABuilder gpaBuilder = new GPABuilder();
        gpaBuilder.setCode(code);
        gpaBuilder.setUnits(units);
        gpaBuilder.setGrade(grade);

        PastCourseDTO pastCourseDTO = new PastCourseDTO();
        pastCourseDTO.setCourseName(code);
        pastCourseDTO.setCredits(units);
        pastCourseDTO.setGrade(grade);

        studentService.addPastCourse(pastCourseDTO);
        courseList.add(gpaBuilder);

        return totalCredits.add(new BigDecimal(units));
    }

    public void processTranscriptForGetCurrentCourses(MultipartFile file) throws IOException {
        String[] line = convertToString(file);
        boolean termFoundLocal = false;

        for (int i = 0; i < line.length; i++) {
            String currentLine = line[i];
            if (termFinder.containsTerm(currentLine)) {
                termFoundLocal = termFinder.containsCurrentTerm(currentLine, currentTerm);
            }

            if (termFoundLocal && codeFinder.containsCourse(currentLine)) {
                String code = codeFinder.normalizeMultiYearCourse(codeFinder.getCourse(currentLine));
                Optional<Course> course = repo.findBycourseCode(code);
                if (course.isEmpty()) continue;
                studentService.addCurrentCourses(course.get(), null);
                try {
                    assessmentTableService.createFromSyllabus(code);
                } catch (Exception e) { /* syllabus may not exist */ }
            }
        }
    }
}
