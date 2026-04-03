package org.tracker.gpatracker.courses.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tracker.gpatracker.courses.dto.CourseLookupDTO;
import org.tracker.gpatracker.courses.service.CourseSearchService;

@Validated
@RestController
@RequestMapping("/api/courses")
public class CourseSearchController {

    private static final Logger logger = LoggerFactory.getLogger(CourseSearchController.class);

    private final CourseSearchService courseSearchService;

    public CourseSearchController(CourseSearchService courseSearchService) {
        this.courseSearchService = courseSearchService;
    }

    @GetMapping("/search")
    public ResponseEntity<Page<CourseLookupDTO>> searchCourses(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(value = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        logger.info("GET /api/courses/search — query: '{}', page: {}", query, page);
        Page<CourseLookupDTO> results = courseSearchService.searchCourses(query, page, size);
        logger.info("GET /api/courses/search — returned {} results", results.getTotalElements());
        return ResponseEntity.ok(results);
    }
}
