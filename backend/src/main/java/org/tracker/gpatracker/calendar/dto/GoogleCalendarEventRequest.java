package org.tracker.gpatracker.calendar.dto;

public record GoogleCalendarEventRequest(
        String summary,
        String description,
        GoogleCalendarDateTime start,
        GoogleCalendarDateTime end
) {
}
