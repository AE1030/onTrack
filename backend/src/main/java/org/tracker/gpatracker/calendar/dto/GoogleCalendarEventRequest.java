package org.tracker.gpatracker.calendar.dto;

public record GoogleCalendarEventRequest(
        String summary,
        String description,
        String location,
        GoogleCalendarDateTime start,
        GoogleCalendarDateTime end
) {
}
