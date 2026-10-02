package org.tracker.gpatracker.accounts.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tracker.gpatracker.accounts.dto.CurrentCourseDTO;
import org.tracker.gpatracker.accounts.dto.PastCourseDTO;
import org.tracker.gpatracker.accounts.dto.UpdateCourseGradeDTO;
import org.tracker.gpatracker.accounts.dto.ToggleGpaDTO;
import org.tracker.gpatracker.accounts.service.ManualUploadService;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.assessmenttable.service.AssessmentTableService;
import org.tracker.gpatracker.syllabus.service.SyllabusUploadQuotaService;
import org.tracker.gpatracker.terms.TermDTO;
import org.tracker.gpatracker.terms.TermService;

import java.util.List;

@RestController
@RequestMapping("/api/my-courses")
public class MyCoursesController {

    private static final Logger logger = LoggerFactory.getLogger(MyCoursesController.class);

    private final ManualUploadService manualUploadService;
    private final SyllabusUploadQuotaService syllabusUploadQuotaService;
    private final StudentService studentService;
    private final AssessmentTableService assessmentTableService;
    private final TermService termService;

    public MyCoursesController(ManualUploadService manualUploadService,
                               SyllabusUploadQuotaService syllabusUploadQuotaService,
                               StudentService studentService,
                               AssessmentTableService assessmentTableService,
                               TermService termService) {
        this.manualUploadService = manualUploadService;
        this.syllabusUploadQuotaService = syllabusUploadQuotaService;
        this.studentService = studentService;
        this.assessmentTableService = assessmentTableService;
        this.termService = termService;
    }

    @GetMapping("/past-courses")
    public ResponseEntity<List<PastCourseDTO>> getPastCourses() {
        List<PastCourseDTO> courses = manualUploadService.getPastCourses();
        return ResponseEntity.ok(courses);
    }


    /**
     * Every term this student has, newest first, for the term picker.
     *
     * <p>The client must not work this list out for itself: the ordering rule and which term
     * counts as current both live on the server.
     */
    @GetMapping("/terms")
    public ResponseEntity<List<TermDTO>> getTerms() {
        return ResponseEntity.ok(termService.listTerms(studentService.getStudentID()));
    }

    /**
     * @param term which term to list. Optional, and omitting it means the current term, so an app
     *             build that predates the term picker keeps working against this.
     */
    @GetMapping("/current-courses")
    public ResponseEntity<List<CurrentCourseDTO>> getPresentCourses(
            @RequestParam(required = false) String term) {
        List<CurrentCourseDTO> courses = manualUploadService.getPresentCourses(term);
        return ResponseEntity.ok(courses);
    }

    @PutMapping("/update-grade")
    public ResponseEntity<Void> updateCourseGrade(@Valid @RequestBody UpdateCourseGradeDTO dto) {
        logger.info("PUT /api/my-courses/update-grade — course: {}, term: {}", dto.getCourseCode(), dto.getTerm());
        studentService.updateCourseGrade(dto.getCourseCode(), dto.getTerm(), dto.getGrade());
        return ResponseEntity.ok().build();
    }

    @PutMapping("/toggle-gpa")
    public ResponseEntity<Void> toggleGpa(@Valid @RequestBody ToggleGpaDTO dto) {
        logger.info("PUT /api/my-courses/toggle-gpa — course: {}, term: {}, include: {}",
                dto.getCourseCode(), dto.getTerm(), dto.isIncludeInGpa());
        studentService.toggleIncludeInGpa(dto.getCourseCode(), dto.getTerm(), dto.isIncludeInGpa());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/delete-course")
    public ResponseEntity<Void> deleteCurrentCourse(@RequestParam String courseCode, @RequestParam String term) {
        logger.info("DELETE /api/my-courses/delete-course?courseCode={}&term={}", courseCode, term);
        // deleteCurrentCourse refuses a past term, so the assessment-table delete below is only
        // reached for a term that was allowed to be written.
        Long studentId = studentService.deleteCurrentCourse(courseCode, term);
        assessmentTableService.deleteByCourseCodeAndTerm(studentId, courseCode, term);
        logger.info("DELETE /api/my-courses — deleted course: {} for term: {}", courseCode, term);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/past-courses")
    public ResponseEntity<Void> deletePastCourse(@RequestParam String courseName) {
        logger.info("DELETE /api/my-courses/past-courses?courseName={}", courseName);
        studentService.deletePastCourse(courseName);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/remaining-uploads")
    public ResponseEntity<Integer> getRemainingUploads() {
        int remaining = syllabusUploadQuotaService.getRemainingUploadsForCurrentStudent();
        return ResponseEntity.ok(remaining);
    }
}
