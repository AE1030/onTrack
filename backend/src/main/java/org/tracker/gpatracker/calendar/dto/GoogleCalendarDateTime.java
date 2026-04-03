package org.tracker.gpatracker.calendar.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GoogleCalendarDateTime(
        @JsonProperty("date") String date,
        @JsonProperty("dateTime") String dateTime,
        @JsonProperty("timeZone") String timeZone
) {
}
