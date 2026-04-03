package org.tracker.gpatracker.calendar.dto;

import org.tracker.gpatracker.calendar.model.CalendarProvider;

public record LinkTokenPayload(Long userId, CalendarProvider provider) {
}
