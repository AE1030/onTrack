package org.tracker.gpatracker.calendar.strategy;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.tracker.gpatracker.calendar.dto.GoogleCalendarDateTime;
import org.tracker.gpatracker.calendar.dto.GoogleCalendarEventRequest;
import org.tracker.gpatracker.calendar.dto.OAuthTokens;
import org.tracker.gpatracker.calendar.model.CalendarEvents.InternalCalendarEvent;
import org.tracker.gpatracker.calendar.model.CalendarProvider;
import org.tracker.gpatracker.calendar.service.GoogleOAuthService;

/** Google Calendar API v3. Events go to the account's primary calendar. */
@Component
public class GoogleCalendarStrategy implements CalendarStrategy {

    private static final String CALENDAR_BASE_URL = "https://www.googleapis.com/calendar/v3";
    private static final String PRIMARY_CALENDAR = "primary";
    private static final String BEARER_PREFIX = "Bearer ";

    private final RestClient restClient;
    private final GoogleOAuthService googleOAuthService;

    public GoogleCalendarStrategy(GoogleOAuthService googleOAuthService) {
        this.googleOAuthService = googleOAuthService;
        this.restClient = RestClient.create();
    }

    @Override
    public CalendarProvider provider() {
        return CalendarProvider.GOOGLE;
    }

    @Override
    public OAuthTokens refreshAccessToken(String refreshToken) {
        return googleOAuthService.refreshAccessToken(refreshToken);
    }

    @Override
    public String createEvent(String accessToken, InternalCalendarEvent event) {
        GoogleCalendarEventResponse response = restClient.post()
                .uri(CALENDAR_BASE_URL + "/calendars/{calendarId}/events", PRIMARY_CALENDAR)
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + accessToken)
                .body(toRequest(event))
                .retrieve()
                .body(GoogleCalendarEventResponse.class);
        if (response == null || response.getId() == null) {
            throw new IllegalStateException("Google calendar event creation failed");
        }
        return response.getId();
    }

    @Override
    public void updateEvent(String accessToken, String externalEventId, InternalCalendarEvent event) {
        restClient.patch()
                .uri(CALENDAR_BASE_URL + "/calendars/{calendarId}/events/{eventId}", PRIMARY_CALENDAR, externalEventId)
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + accessToken)
                .body(toRequest(event))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void deleteEvent(String accessToken, String externalEventId) {
        restClient.delete()
                .uri(CALENDAR_BASE_URL + "/calendars/{calendarId}/events/{eventId}", PRIMARY_CALENDAR, externalEventId)
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + accessToken)
                .retrieve()
                .toBodilessEntity();
    }

    private GoogleCalendarEventRequest toRequest(InternalCalendarEvent event) {
        String summary = event.getCourseCode() + " - " + event.getAssessmentName();
        String description = event.getDescription();

        GoogleCalendarDateTime start;
        GoogleCalendarDateTime end;

        if (event.isAllDay()) {
            start = new GoogleCalendarDateTime(event.getDueDate(), null, null);
            end = new GoogleCalendarDateTime(event.getDueDate(), null, null);
        } else {
            String startDateTime = event.getDueDate() + "T" + event.getStartTime() + ":00";
            String endDateTime;
            if (event.getEndTime() != null && !event.getEndTime().isBlank()) {
                endDateTime = event.getDueDate() + "T" + event.getEndTime() + ":00";
            } else {
                endDateTime = startDateTime;
            }
            start = new GoogleCalendarDateTime(null, startDateTime, null);
            end = new GoogleCalendarDateTime(null, endDateTime, null);
        }

        return new GoogleCalendarEventRequest(summary, description, start, end);
    }
}
