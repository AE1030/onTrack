package org.tracker.gpatracker.accounts.service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final int currentTermYear;
    private final String currentTermSeason;

    private final CourseRepository repo;
    private final StudentService studentService;
    private final AssessmentTableService assessmentTableService;

    public TranscriptUploadService(CourseRepository repo, StudentService studentService,
                                   AssessmentTableService assessmentTableService,
                                   @Value("${app.current-term.year}") int currentTermYear,
                                   @Value("${app.current-term.season}") String currentTermSeason) {
        this.repo = repo;
        this.studentService = studentService;
        this.assessmentTableService = assessmentTableService;
        gradeFinder = new GradeFinder();
        codeFinder = new CodeFinder();
        gradeDict = new GradeDict();
        gpaCalc = new GPACalc();
        unitFinder = new UnitFinder();
        termFinder = new TermFinder();
        // Year and season are configured separately so the parser never has to reproduce the
        // transcript's header formatting. This used to be the literal "--- 2026 Winter ---",
        // which both ignored the configured term and pinned the parser to one exact rendering.
        this.currentTermYear = currentTermYear;
        this.currentTermSeason = currentTermSeason;
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
        boolean anyCourseFound = false;

        for (int i = 0; i < line.length; i++) {
            String currentLine = line[i];
            // Every term header both opens and closes the current-term window: the header for
            // the configured term opens it, and the next header of any term closes it. Without
            // that second half the window ran to the end of the transcript, so courses from
            // terms after the current one were enrolled as if they were current.
            if (termFinder.containsTerm(currentLine)) {
                termFound = termFinder.containsCurrentTerm(currentLine, currentTermYear, currentTermSeason);
                continue;
            }

            if (codeFinder.containsCourse(currentLine)) {
                anyCourseFound = true;
                String code = codeFinder.normalizeMultiYearCourse(codeFinder.getCourse(currentLine));
                if (termFound) {
                    enrollCurrentTermCourse(code);
                }
                totalCredits = recordPastCourse(code, line, i, courseList, totalCredits);
            }
        }

        // A first-year transcript is a legitimate upload with zero graded courses: the current
        // term is in progress, so nothing carries a grade yet. Only a file with no course codes
        // at all is unparseable. Throwing on an empty courseList used to roll back the
        // transaction, discarding the current-term enrollments this same pass had just made.
        if (!anyCourseFound) {
            throw new IllegalStateException("No courses found in uploaded file.");
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

    /**
     * The units and letter grade sitting under a course code, or null if the course has neither.
     *
     * <p>Scans forward until the next course code or term header, since a transcript lays each
     * course out as a small block rather than a single line.
     *
     * @return {@code {units, grade}}, or null when either is missing — an in-progress course
     */
    private String[] findUnitsAndGrade(String[] line, int startIndex) {
        String units = null;
        String grade = null;

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

        return (units == null || grade == null) ? null : new String[]{units, grade};
    }

    /**
     * The 12-point GPA a transcript works out to, and nothing else.
     *
     * <p>Written for the leaderboard, which needs a verified starting point at the moment a student
     * joins a season and must not have that reading disturb anything. {@link
     * #processTranscriptForGPACalc} cannot be reused for it: that method clears and rewrites past
     * courses, enrols current-term courses, and overwrites {@code Student.gpa12} as a side effect
     * of parsing. Onboarding wants the number, not the rewrite.
     *
     * <p>A first-year transcript legitimately yields 0 here — the current term is in progress and
     * nothing carries a grade yet — and that zero is a real baseline, not a parse failure. A file
     * with no course codes at all is the parse failure, and throws.
     *
     * @throws IllegalStateException if the file contains no recognisable courses
     */
    public BigDecimal parseGpa12(MultipartFile file) throws IOException {
        String[] line = convertToString(file);

        List<GPABuilder> courseList = new ArrayList<>();
        boolean anyCourseFound = false;

        for (int i = 0; i < line.length; i++) {
            if (termFinder.containsTerm(line[i]) || !codeFinder.containsCourse(line[i])) {
                continue;
            }
            anyCourseFound = true;
            String code = codeFinder.normalizeMultiYearCourse(codeFinder.getCourse(line[i]));

            String[] unitsAndGrade = findUnitsAndGrade(line, i);
            if (unitsAndGrade == null) {
                continue;
            }
            // McMaster's retake policy: only the latest attempt counts, so a repeated code
            // replaces the earlier one rather than being averaged with it.
            courseList.removeIf(existing -> existing.getCode().equals(code));

            GPABuilder builder = new GPABuilder();
            builder.setCode(code);
            builder.setUnits(unitsAndGrade[0]);
            builder.setGrade(unitsAndGrade[1]);
            courseList.add(builder);
        }

        if (!anyCourseFound) {
            throw new IllegalStateException("No courses found in uploaded file.");
        }

        return gpaCalc.getGPA(courseList, gradeDict.getMacGradeDict());
    }

    private BigDecimal recordPastCourse(String code, String[] line, int startIndex,
                                        List<GPABuilder> courseList, BigDecimal totalCredits) {
        String[] unitsAndGrade = findUnitsAndGrade(line, startIndex);
        if (unitsAndGrade == null) return totalCredits;

        String units = unitsAndGrade[0];
        String grade = unitsAndGrade[1];

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
                termFoundLocal = termFinder.containsCurrentTerm(currentLine, currentTermYear, currentTermSeason);
                continue;
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
