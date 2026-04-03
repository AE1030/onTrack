package org.tracker.gpatracker.accounts.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tracker.gpatracker.accounts.dto.AddCurrentCourseDTO;
import org.tracker.gpatracker.accounts.dto.GPACalculatorResponse;
import org.tracker.gpatracker.accounts.dto.PastCourseDTO;
import org.tracker.gpatracker.accounts.service.ManualUploadService;
import org.tracker.gpatracker.security.model.UserPrincipal;

import java.util.List;

@RestController
@RequestMapping("/api/manual-upload")
public class ManualUploadController {

    private static final Logger logger = LoggerFactory.getLogger(ManualUploadController.class);

    private final ManualUploadService manualUploadService;

    public ManualUploadController(ManualUploadService manualUploadService) {
        this.manualUploadService = manualUploadService;
    }

    @PostMapping("/calculate-gpa")
    public ResponseEntity<GPACalculatorResponse> addPastCourses(
            @Valid @RequestBody List<PastCourseDTO> courseList,
            @AuthenticationPrincipal UserPrincipal user) {

        logger.info("POST /api/manual-upload/calculate-gpa — {} courses submitted", courseList.size());
        return ResponseEntity.ok(manualUploadService.calculateGPA(courseList));
    }

    @PostMapping("/add-current-courses")
    public ResponseEntity<Void> addCurrentCourses(
            @Valid @RequestBody List<AddCurrentCourseDTO> courseList) {

        logger.info("POST /api/manual-upload/add-current-courses — {} courses submitted", courseList.size());
        manualUploadService.addCurrentCourses(courseList);
        return ResponseEntity.ok().build();
    }





}
