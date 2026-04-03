package org.tracker.gpatracker.calendar.service;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.tracker.gpatracker.calendar.dto.GoogleCalendarEventRequest;

@Service
public class GoogleCalendarService {

    private static final String CALENDAR_BASE_URL = "https://www.googleapis.com/calendar/v3";
    private static final String BEARER_PREFIX = "Bearer ";
    private final RestClient restClient;

    public GoogleCalendarService() {
        this.restClient = RestClient.create();
    }

    public String createEvent(String accessToken, String calendarId, GoogleCalendarEventRequest request) {
        GoogleCalendarEventResponse response = restClient.post()
                .uri(CALENDAR_BASE_URL + "/calendars/{calendarId}/events", calendarId)
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + accessToken)
                .body(request)
                .retrieve()
                .body(GoogleCalendarEventResponse.class);
        if (response == null || response.getId() == null) {
            throw new IllegalStateException("Google calendar event creation failed");
        }
        return response.getId();
    }

    public String updateEvent(String accessToken, String calendarId, String eventId, GoogleCalendarEventRequest request) {
        GoogleCalendarEventResponse response = restClient.patch()
                .uri(CALENDAR_BASE_URL + "/calendars/{calendarId}/events/{eventId}", calendarId, eventId)
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + accessToken)
                .body(request)
                .retrieve()
                .body(GoogleCalendarEventResponse.class);
        if (response == null || response.getId() == null) {
            throw new IllegalStateException("Google calendar event update failed");
        }
        return response.getId();
    }

    public void deleteEvent(String accessToken, String calendarId, String eventId) {
        restClient.delete()
                .uri(CALENDAR_BASE_URL + "/calendars/{calendarId}/events/{eventId}", calendarId, eventId)
                .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + accessToken)
                .retrieve()
                .toBodilessEntity();
    }

}
