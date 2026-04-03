package org.tracker.gpatracker.assessmenttable.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tracker.gpatracker.assessmenttable.dto.SaveAssessmentTableDTO;
import org.tracker.gpatracker.syllabus.dto.AssessmentTableDTO;
import org.tracker.gpatracker.assessmenttable.service.AssessmentTableService;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/assessment-table")
public class AssessmentTableController {

    private static final Logger logger = LoggerFactory.getLogger(AssessmentTableController.class);

    private final AssessmentTableService assessmentTableService;

    public AssessmentTableController(AssessmentTableService assessmentTableService) {
        this.assessmentTableService = assessmentTableService;
    }

    @PostMapping("/save")
    public ResponseEntity<Void> saveAssessmentTable(@Valid @RequestBody SaveAssessmentTableDTO assessmentTableDTO) {
        logger.info("POST /api/assessment-table/save — course: {}", assessmentTableDTO.getCourseCode());
        assessmentTableService.saveToRepo(assessmentTableDTO);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/get-assessment-table")
    public ResponseEntity<Map<String, List<AssessmentTableDTO>>> getAssessmentTable(@RequestParam String courseCode, @RequestParam String term) {
        Map<String, List<AssessmentTableDTO>> result =
                assessmentTableService.getByStudentIdAndCourseCodeAndTerm(courseCode, term);
        if (result == null || result.isEmpty()) {
            return ResponseEntity.status(404).body(null);
        }
        return ResponseEntity.ok(result);
    }
}
