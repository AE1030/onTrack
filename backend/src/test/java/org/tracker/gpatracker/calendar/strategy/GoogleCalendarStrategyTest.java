package org.tracker.gpatracker.calendar.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tracker.gpatracker.calendar.dto.GoogleCalendarEventRequest;
import org.tracker.gpatracker.calendar.model.CalendarEvents.InternalCalendarEvent;
import org.tracker.gpatracker.calendar.service.GoogleOAuthService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class GoogleCalendarStrategyTest {

    private final GoogleCalendarStrategy strategy =
            new GoogleCalendarStrategy(mock(GoogleOAuthService.class), "America/Toronto");

    @Test
    @DisplayName("an all-day event ends the next day, since Google's end date is exclusive")
    void allDayEndIsExclusive() {
        GoogleCalendarEventRequest req = strategy.toRequest(event("2026-10-31", null, null));

        assertThat(req.start().date()).isEqualTo("2026-10-31");
        assertThat(req.end().date()).isEqualTo("2026-11-01");
        assertThat(req.start().dateTime()).isNull();
        assertThat(req.start().timeZone()).isNull();
    }

    @Test
    @DisplayName("an all-day event on the last day of the year rolls into the next year")
    void allDayYearBoundary() {
        assertThat(strategy.toRequest(event("2026-12-31", null, null)).end().date()).isEqualTo("2027-01-01");
    }

    @Test
    @DisplayName("a timed event carries the configured zone on both ends")
    void timedEventHasZone() {
        GoogleCalendarEventRequest req = strategy.toRequest(event("2026-10-21", "10:00", "11:30"));

        assertThat(req.start().dateTime()).isEqualTo("2026-10-21T10:00:00");
        assertThat(req.end().dateTime()).isEqualTo("2026-10-21T11:30:00");
        assertThat(req.start().timeZone()).isEqualTo("America/Toronto");
        assertThat(req.end().timeZone()).isEqualTo("America/Toronto");
        assertThat(req.start().date()).isNull();
    }

    @Test
    @DisplayName("a timed event with no end time is a zero-length event at the due time")
    void timedWithoutEnd() {
        GoogleCalendarEventRequest req = strategy.toRequest(event("2026-10-21", "23:59", null));

        assertThat(req.end().dateTime()).isEqualTo(req.start().dateTime());
        assertThat(req.end().timeZone()).isEqualTo("America/Toronto");
    }

    @Test
    @DisplayName("location is sent")
    void locationIsSent() {
        InternalCalendarEvent e = event("2026-10-21", null, null);
        e.setLocation("EITC E2 130");

        assertThat(strategy.toRequest(e).location()).isEqualTo("EITC E2 130");
    }

    @Test
    @DisplayName("an unknown zone fails at construction rather than on every sync")
    void badZoneFailsFast() {
        assertThatThrownBy(() -> new GoogleCalendarStrategy(mock(GoogleOAuthService.class), "Toronto"))
                .isInstanceOf(java.time.DateTimeException.class);
    }

    private static InternalCalendarEvent event(String dueDate, String startTime, String endTime) {
        InternalCalendarEvent e = new InternalCalendarEvent();
        e.setCourseCode("COMP 2140");
        e.setAssessmentName("Midterm");
        e.setDueDate(dueDate);
        e.setAllDay(startTime == null);
        e.setStartTime(startTime);
        e.setEndTime(endTime);
        return e;
    }
}
