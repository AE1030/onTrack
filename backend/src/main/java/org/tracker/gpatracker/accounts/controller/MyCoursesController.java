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

import java.util.List;

@RestController
@RequestMapping("/api/my-courses")
public class MyCoursesController {

    private static final Logger logger = LoggerFactory.getLogger(MyCoursesController.class);

    private final ManualUploadService manualUploadService;
    private final SyllabusUploadQuotaService syllabusUploadQuotaService;
    private final StudentService studentService;
    private final AssessmentTableService assessmentTableService;

    public MyCoursesController(ManualUploadService manualUploadService,
                               SyllabusUploadQuotaService syllabusUploadQuotaService,
                               StudentService studentService,
                               AssessmentTableService assessmentTableService) {
        this.manualUploadService = manualUploadService;
        this.syllabusUploadQuotaService = syllabusUploadQuotaService;
        this.studentService = studentService;
        this.assessmentTableService = assessmentTableService;
    }

    @GetMapping("/past-courses")
    public ResponseEntity<List<PastCourseDTO>> getPastCourses() {
        List<PastCourseDTO> courses = manualUploadService.getPastCourses();
        return ResponseEntity.ok(courses);
    }


    @GetMapping("/current-courses")
    public ResponseEntity<List<CurrentCourseDTO>> getPresentCourses(){
        List<CurrentCourseDTO> courses = manualUploadService.getPresentCourses();
        return ResponseEntity.ok(courses);
    }

    @PutMapping("/update-grade")
    public ResponseEntity<Void> updateCourseGrade(@Valid @RequestBody UpdateCourseGradeDTO dto) {
        logger.info("PUT /api/my-courses/update-grade — course: {}", dto.getCourseCode());
        studentService.updateCourseGrade(dto.getCourseCode(), dto.getGrade());
        return ResponseEntity.ok().build();
    }

    @PutMapping("/toggle-gpa")
    public ResponseEntity<Void> toggleGpa(@Valid @RequestBody ToggleGpaDTO dto) {
        logger.info("PUT /api/my-courses/toggle-gpa — course: {}, include: {}", dto.getCourseCode(), dto.isIncludeInGpa());
        studentService.toggleIncludeInGpa(dto.getCourseCode(), dto.isIncludeInGpa());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/delete-course")
    public ResponseEntity<Void> deleteCurrentCourse(@RequestParam String courseCode, @RequestParam String term) {
        logger.info("DELETE /api/my-courses/delete-course?courseCode={}&term={}", courseCode, term);
        Long studentId = studentService.deleteCurrentCourse(courseCode);
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
