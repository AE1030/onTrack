package org.tracker.gpatracker.calendar.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.calendar.dto.CalendarAccountStatus;
import org.tracker.gpatracker.calendar.exception.CalendarSyncInProgressException;
import org.tracker.gpatracker.calendar.model.CalendarEvents;
import org.tracker.gpatracker.calendar.model.CalendarProvider;
import org.tracker.gpatracker.calendar.service.CalendarProjectionService;
import org.tracker.gpatracker.calendar.service.CalendarSyncService;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/calendar")
public class CalendarUserInterfaceController {

    private static final Logger logger = LoggerFactory.getLogger(CalendarUserInterfaceController.class);

    private final CalendarProjectionService calendarProjectionService;
    private final CalendarSyncService calendarSyncService;
    private final StudentService studentService;

    public CalendarUserInterfaceController(
            CalendarProjectionService calendarProjectionService,
            CalendarSyncService calendarSyncService,
            StudentService studentService
    ) {
        this.calendarProjectionService = calendarProjectionService;
        this.calendarSyncService = calendarSyncService;
        this.studentService = studentService;
    }

    /**
     * Connected external calendars, for every provider. The app polls this to warn the student
     * when a background sync found an account's access revoked.
     */
    @GetMapping("/accounts")
    public List<CalendarAccountStatus> getAccounts() {
        return calendarSyncService.accountStatuses(studentService.getStudentID());
    }

    /**
     * Disconnects one provider. With {@code removeEvents}, the events onTrack put in that calendar
     * are deleted first; without it they stay there but stop being updated.
     *
     * <p>404 for an unknown provider or one the student never connected, 409 while a sync of that
     * account is running.
     */
    @DeleteMapping("/accounts/{provider}")
    public ResponseEntity<?> disconnect(
            @PathVariable String provider,
            @RequestParam(value = "removeEvents", defaultValue = "false") boolean removeEvents
    ) {
        CalendarProvider parsed;
        try {
            parsed = CalendarProvider.valueOf(provider.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        Long studentId = studentService.getStudentID();
        try {
            return calendarSyncService.disconnect(studentId, parsed, removeEvents)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (CalendarSyncInProgressException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Your calendar is syncing right now. Please try again in a moment."));
        }
    }

    @GetMapping("/events")
    public ResponseEntity<CalendarEvents> getCalendarEvents() {
        logger.info("GET /api/calendar/events");
        Long studentId = studentService.getStudentID();
        return calendarProjectionService.getCalendarEvents(studentId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
