package org.tracker.gpatracker.calendar.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class CalendarConnectRequest {
    @NotBlank(message = "Calendar email is required")
    @Email(message = "Invalid email format")
    private String calendarEmail;

    public String getCalendarEmail() {
        return calendarEmail;
    }
    public void setCalendarEmail(String calendarEmail) {
        this.calendarEmail = calendarEmail;
    }
}
