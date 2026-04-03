package org.tracker.gpatracker.accounts.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tracker.gpatracker.accounts.dto.DashboardDTO;
import org.tracker.gpatracker.accounts.dto.SetTargetGpaDTO;
import org.tracker.gpatracker.accounts.service.DashboardService;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.calendar.model.CalendarEvents.InternalCalendarEvent;
import org.tracker.gpatracker.calendar.service.CalendarProjectionService;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);

    private final DashboardService dashboardService;
    private final CalendarProjectionService calendarProjectionService;
    private final StudentService studentService;

    public DashboardController(DashboardService dashboardService,
                               CalendarProjectionService calendarProjectionService,
                               StudentService studentService) {
        this.dashboardService = dashboardService;
        this.calendarProjectionService = calendarProjectionService;
        this.studentService = studentService;
    }

    @GetMapping
    public ResponseEntity<DashboardDTO> getDashboard() {
        return ResponseEntity.ok(dashboardService.getDashboardData());
    }

    @PutMapping("/target-gpa")
    public ResponseEntity<Void> setTargetGpa(@Valid @RequestBody SetTargetGpaDTO dto) {
        logger.info("PUT /api/dashboard/target-gpa");
        dashboardService.setTargetGpa(dto);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/upcoming-events")
    public ResponseEntity<List<InternalCalendarEvent>> getUpcomingEvents() {
        Long studentId = studentService.getStudentID();
        return ResponseEntity.ok(calendarProjectionService.getUpcomingEvents(studentId));
    }

    @PutMapping("/upcoming-events/{eventKey}")
    public ResponseEntity<Void> updateUpcomingEvent(@PathVariable String eventKey) {
        calendarProjectionService.updateUpcomingEvent(eventKey);
        return ResponseEntity.ok().build();
    }
}
