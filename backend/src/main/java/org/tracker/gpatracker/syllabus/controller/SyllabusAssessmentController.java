package org.tracker.gpatracker.syllabus.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tracker.gpatracker.syllabus.dto.AssessmentTableDTO;
import org.tracker.gpatracker.syllabus.model.AbstractSyllabusDocument;
import org.tracker.gpatracker.syllabus.service.SyllabusAssessmentService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/syllabus")
public class SyllabusAssessmentController {

    private static final Logger logger = LoggerFactory.getLogger(SyllabusAssessmentController.class);

    private final SyllabusAssessmentService service;

    public SyllabusAssessmentController(SyllabusAssessmentService service) {
        this.service = service;
    }


    @GetMapping("/assessments")
    public ResponseEntity<Map<String, List<AssessmentTableDTO>>> getNormalizedAssessments(
            @RequestParam String courseCode,
            @RequestParam String term
    ) {
        logger.info("GET /api/syllabus/assessments?courseCode={}&term={}", courseCode, term);
        AbstractSyllabusDocument doc = service.getSyllabusDocument(courseCode, term);
        return ResponseEntity.ok(service.getNormalizedAssessmentItems(doc));
    }

}
