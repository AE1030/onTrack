package org.tracker.gpatracker.calendar.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.assessmenttable.model.AssessmentScheme;
import org.tracker.gpatracker.assessmenttable.model.AssessmentTableDocument;
import org.tracker.gpatracker.assessmenttable.model.SchemeAssessment;
import org.tracker.gpatracker.assessmenttable.repository.AssessmentTableDocumentRepository;
import org.tracker.gpatracker.calendar.model.AbstractCalendarEvent;
import org.tracker.gpatracker.calendar.model.CalendarEvents;
import org.tracker.gpatracker.calendar.model.CalendarEvents.InternalCalendarEvent;
import org.tracker.gpatracker.calendar.repository.CalendarEventsRepository;
import org.tracker.gpatracker.terms.TermService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalendarProjectionServiceTest {

    private static final Long STUDENT = 1L;
    private static final String TERM = "Fall 2026";

    private AssessmentTableDocumentRepository tableRepository;
    private CalendarEventsRepository eventsRepository;
    private StudentService studentService;
    private CalendarProjectionService service;

    @BeforeEach
    void setUp() {
        tableRepository = mock(AssessmentTableDocumentRepository.class);
        eventsRepository = mock(CalendarEventsRepository.class);
        TermService termService = mock(TermService.class);
        when(termService.getCurrentTerm()).thenReturn(TERM);
        studentService = mock(StudentService.class);
        service = new CalendarProjectionService(tableRepository, eventsRepository,
                mock(ApplicationEventPublisher.class), studentService, termService);
    }

    @Test
    @DisplayName("events marked done stay done after a rebuild; a moved due date comes back not done")
    void completedFlagSurvivesRecompute() {
        table("COMP 2140",
                assessment("Midterm", "2026-10-21"),
                assessment("Quiz 1", "2026-10-28"),
                assessment("Final", "2026-12-10"));
        existingProjection(
                done("COMP 2140", "Midterm", "2026-10-21"),
                done("COMP 2140", "Quiz 1", "2026-10-24"), // since moved to the 28th
                notDone("COMP 2140", "Final", "2026-12-10"));

        service.recompute(STUDENT);

        Map<String, Boolean> completed = savedEvents().stream()
                .collect(Collectors.toMap(InternalCalendarEvent::getAssessmentName, InternalCalendarEvent::isCompleted));
        assertThat(completed).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Midterm", true,
                "Quiz 1", false,
                "Final", false));
    }

    @Test
    @DisplayName("a first build with no existing projection marks nothing done")
    void firstBuildHasNothingDone() {
        table("COMP 2140", assessment("Midterm", "2026-10-21"));
        when(eventsRepository.findByOwnerId(STUDENT)).thenReturn(Optional.empty());

        service.recompute(STUDENT);

        assertThat(savedEvents()).extracting(InternalCalendarEvent::isCompleted).containsExactly(false);
    }

    @Test
    @DisplayName("a row with a null events list reads as no upcoming events, and marking done is a no-op")
    void nullEventsListIsEmpty() {
        CalendarEvents legacy = new CalendarEvents();
        legacy.setOwnerId(STUDENT);
        when(eventsRepository.findByOwnerId(STUDENT)).thenReturn(Optional.of(legacy));
        when(studentService.getStudentID()).thenReturn(STUDENT);

        assertThat(service.getUpcomingEvents(STUDENT)).isEmpty();
        service.updateUpcomingEvent("COMP 2140|Midterm|2026-10-21");
        verify(eventsRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ fixtures

    private void table(String courseCode, SchemeAssessment... assessments) {
        AssessmentScheme scheme = new AssessmentScheme();
        scheme.setSchemeName("Default");
        scheme.setAssessments(new ArrayList<>(List.of(assessments)));
        AssessmentTableDocument table = new AssessmentTableDocument();
        table.setCourseCode(courseCode);
        table.setTerm(TERM);
        table.setSchemes(List.of(scheme));
        when(tableRepository.findByOwnerId(STUDENT)).thenReturn(List.of(table));
    }

    private void existingProjection(InternalCalendarEvent... events) {
        CalendarEvents projection = new CalendarEvents();
        projection.setOwnerId(STUDENT);
        projection.setEvents(new ArrayList<>(List.of(events)));
        when(eventsRepository.findByOwnerId(STUDENT)).thenReturn(Optional.of(projection));
    }

    private List<InternalCalendarEvent> savedEvents() {
        ArgumentCaptor<CalendarEvents> captor = ArgumentCaptor.forClass(CalendarEvents.class);
        verify(eventsRepository).save(captor.capture());
        return captor.getValue().getEvents();
    }

    private static SchemeAssessment assessment(String name, String dueDate) {
        SchemeAssessment a = new SchemeAssessment();
        a.setName(name);
        a.setDueDate(dueDate);
        return a;
    }

    private static InternalCalendarEvent done(String course, String name, String date) {
        InternalCalendarEvent e = notDone(course, name, date);
        e.setCompleted(true);
        return e;
    }

    private static InternalCalendarEvent notDone(String course, String name, String date) {
        InternalCalendarEvent e = new InternalCalendarEvent();
        e.setCourseCode(course);
        e.setAssessmentName(name);
        e.setDueDate(date);
        e.setEventKey(AbstractCalendarEvent.buildEventKey(course, name, date));
        return e;
    }
}
