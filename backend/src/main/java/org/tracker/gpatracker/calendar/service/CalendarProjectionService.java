package org.tracker.gpatracker.calendar.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.tracker.gpatracker.assessmenttable.model.AssessmentScheme;
import org.tracker.gpatracker.assessmenttable.model.AssessmentTableDocument;
import org.tracker.gpatracker.assessmenttable.model.SchemeAssessment;
import org.tracker.gpatracker.assessmenttable.repository.AssessmentTableDocumentRepository;
import org.tracker.gpatracker.calendar.event.AssessmentTableUpdatedEvent;
import org.tracker.gpatracker.calendar.model.AbstractCalendarEvent;
import org.tracker.gpatracker.calendar.model.CalendarEvents;
import org.tracker.gpatracker.calendar.model.CalendarEvents.InternalCalendarEvent;
import org.tracker.gpatracker.calendar.repository.CalendarEventsRepository;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.terms.TermService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class CalendarProjectionService {

    private static final Logger logger = LoggerFactory.getLogger(CalendarProjectionService.class);
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final AssessmentTableDocumentRepository assessmentTableRepository;
    private final CalendarEventsRepository calendarEventsRepository;
    private final GoogleCalendarSyncService googleCalendarSyncService;
    private final StudentService studentService;
    private final TermService termService;

    public CalendarProjectionService(
            AssessmentTableDocumentRepository assessmentTableRepository,
            CalendarEventsRepository calendarEventsRepository,
            GoogleCalendarSyncService googleCalendarSyncService,
            StudentService studentService,
            TermService termService
    ) {
        this.assessmentTableRepository = assessmentTableRepository;
        this.calendarEventsRepository = calendarEventsRepository;
        this.googleCalendarSyncService = googleCalendarSyncService;
        this.studentService = studentService;
        this.termService = termService;
    }

    @EventListener
    public void updateCalendarProjection(AssessmentTableUpdatedEvent event) {
        Long studentId = event.getStudentId();
        logger.info("Recomputing calendar projection for student {}", studentId);
        recompute(studentId);

        try {
            googleCalendarSyncService.sync(studentId);
        } catch (Exception e) {
            logger.warn("Google Calendar sync skipped for student {}: {}", studentId, e.getMessage());
        }
    }

    public List<InternalCalendarEvent> getUpcomingEvents(Long studentId) {
        Optional<CalendarEvents> calendarEvents = calendarEventsRepository.findByOwnerId(studentId);
        if (calendarEvents.isEmpty()) {
            return List.of();
        }

        String today = java.time.LocalDate.now().toString();

        return calendarEvents.get().getEvents().stream()
                .filter(event -> !event.isCompleted())
                .filter(event -> event.getDueDate() != null && event.getDueDate().compareTo(today) >= 0)
                .toList();
    }

    public void updateUpcomingEvent(String eventKey) {
        Long studentId = studentService.getStudentID();
        CalendarEvents calendarEvents = calendarEventsRepository.findByOwnerId(studentId)
                .orElseThrow(() -> new RuntimeException("No calendar events found for student " + studentId));

        List<InternalCalendarEvent> events = calendarEvents.getEvents();
        for (InternalCalendarEvent event : events) {
            if (eventKey.equals(event.getEventKey())) {
                event.setCompleted(true);
                break;
            }
        }

        calendarEventsRepository.save(calendarEvents);
    }

    public Optional<CalendarEvents> getCalendarEvents(Long studentId) {
        Optional<CalendarEvents> existing = calendarEventsRepository.findByOwnerId(studentId);
        if (existing.isPresent()) {
            return existing;
        }

        // First fetch — no projection exists yet. Build it on-demand from existing assessment tables.
        recompute(studentId);
        return calendarEventsRepository.findByOwnerId(studentId);
    }

    /**
     * Full rebuild of the calendar projection from all assessment tables.
     * Since each assessment table save replaces the entire table (not per-assessment),
     * this method rebuilds the complete event list from scratch every time —
     * ensuring removed/edited assessments are reflected immediately.
     */
    public void recompute(Long studentId) {
        List<AssessmentTableDocument> tables = assessmentTableRepository.findByOwnerId(studentId);
        String currentTerm = termService.getCurrentTerm();

        // Build the entire event list from the current state of all assessment tables.
        // This is a full replacement — any assessment that no longer exists in the
        // tables will simply not appear, effectively removing it from the projection.
        //
        // Current term only. The dedupe below keys on course code plus assessment name, so the
        // same course in two terms would collide: putIfAbsent lets whichever table was read
        // first win and the other term's date disappears. A past term has nothing coming up
        // either way, so filtering here is both the fix and the correct behaviour.
        Map<String, InternalCalendarEvent> deduped = new LinkedHashMap<>();
        for (AssessmentTableDocument table : tables) {
            if (table == null || !currentTerm.equals(table.getTerm())) {
                continue;
            }
            collectEventsFromTable(table, deduped);
        }


        // Replace the entire events list — not a merge, a full replacement.
        // This guarantees deleted/edited assessments don't linger.
        CalendarEvents calendarEvents = calendarEventsRepository.findByOwnerId(studentId)
                .orElseGet(CalendarEvents::new);
        calendarEvents.setOwnerId(studentId);
        calendarEvents.setEvents(new ArrayList<>(deduped.values()));
        calendarEvents.setLastUpdatedAt(Instant.now());

        calendarEventsRepository.save(calendarEvents);
        logger.info("Calendar projection rebuilt for student {} — {} events (full replacement)", studentId, deduped.size());
    }

    private void collectEventsFromTable(AssessmentTableDocument table, Map<String, InternalCalendarEvent> deduped) {
        List<AssessmentScheme> schemes = table.getSchemes();
        if (schemes == null) return;

        String courseCode = table.getCourseCode();
        for (AssessmentScheme scheme : schemes) {
            if (scheme == null || scheme.getAssessments() == null) continue;
            for (SchemeAssessment assessment : scheme.getAssessments()) {
                if (assessment == null) continue;
                InternalCalendarEvent calEvent = toCalendarEvent(courseCode, assessment);
                if (calEvent != null) {
                    deduped.putIfAbsent(calEvent.getEventKey(), calEvent);
                }
            }
        }
    }

    private InternalCalendarEvent toCalendarEvent(String courseCode, SchemeAssessment assessment) {
        String dueDate = assessment.getDueDate();
        if (dueDate == null || !DATE_PATTERN.matcher(dueDate).matches()) {
            return null;
        }

        InternalCalendarEvent event = new InternalCalendarEvent();
        event.setCourseCode(courseCode);
        event.setAssessmentName(assessment.getName());
        event.setDueDate(dueDate);
        String description = assessment.getDescription();
        if (description == null || description.isBlank()) {
            description = courseCode + " - " + assessment.getName();
        }
        event.setDescription(description);
        event.setLocation(assessment.getLocation());

        String startTime = assessment.getStartTime();
        if (startTime == null || startTime.isBlank()) {
            event.setAllDay(true);
        } else {
            event.setAllDay(false);
            event.setStartTime(startTime);
            event.setEndTime(assessment.getEndTime());
        }

        event.setEventKey(AbstractCalendarEvent.buildEventKey(courseCode, assessment.getName(), dueDate));
        return event;
    }
}
