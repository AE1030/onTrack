package org.tracker.gpatracker.calendar.strategy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.tracker.gpatracker.calendar.dto.GoogleCalendarDateTime;
import org.tracker.gpatracker.calendar.dto.GoogleCalendarEventRequest;
import org.tracker.gpatracker.calendar.dto.OAuthTokens;
import org.tracker.gpatracker.calendar.model.CalendarEvents.InternalCalendarEvent;
import org.tracker.gpatracker.calendar.model.CalendarProvider;
import org.tracker.gpatracker.calendar.service.GoogleOAuthService;

import java.time.LocalDate;
import java.time.ZoneId;

/** Google Calendar API v3. Events go to the account's primary calendar. */
@Component
public class GoogleCalendarStrategy implements CalendarStrategy {

    private static final String CALENDAR_BASE_URL = "https://www.googleapis.com/calendar/v3";
    private static final String PRIMARY_CALENDAR = "primary";
    private static final String BEARER_PREFIX = "Bearer ";

    private final RestClient restClient;
    private final GoogleOAuthService googleOAuthService;
    /** IANA zone the app's wall-clock times are in. Validated here so a typo fails startup. */
    private final String timeZone;

    public GoogleCalendarStrategy(GoogleOAuthService googleOAuthService,
                                  @Value("${calendar.timezone:America/Toronto}") String timeZone) {
        this.googleOAuthService = googleOAuthService;
        this.timeZone = ZoneId.of(timeZone).getId();
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
        try {
            restClient.delete()
                    .uri(CALENDAR_BASE_URL + "/calendars/{calendarId}/events/{eventId}", PRIMARY_CALENDAR, externalEventId)
                    .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + accessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            // 410 Gone (already deleted) or 404 Not Found: the student removed it in Google
            // themselves. The event is gone either way, which is all a delete is for; treating it
            // as a failure would keep it in the snapshot and retry it on every sync.
            int status = e.getStatusCode().value();
            if (status != 404 && status != 410) {
                throw e;
            }
        }
    }

    GoogleCalendarEventRequest toRequest(InternalCalendarEvent event) {
        String summary = event.getCourseCode() + " - " + event.getAssessmentName();
        String description = event.getDescription();

        GoogleCalendarDateTime start;
        GoogleCalendarDateTime end;

        if (event.isAllDay()) {
            // Google's end date is exclusive: a one-day event ends the day after it starts.
            // Sending the same date for both is an empty range, which Google rejects.
            String nextDay = LocalDate.parse(event.getDueDate()).plusDays(1).toString();
            start = new GoogleCalendarDateTime(event.getDueDate(), null, null);
            end = new GoogleCalendarDateTime(nextDay, null, null);
        } else {
            // Our times are wall-clock times with no offset, so Google needs the zone to place
            // them; it also applies daylight saving for that date itself. No end time means a
            // zero-length event at the due time.
            String startDateTime = event.getDueDate() + "T" + event.getStartTime() + ":00";
            String endDateTime;
            if (event.getEndTime() != null && !event.getEndTime().isBlank()) {
                endDateTime = event.getDueDate() + "T" + event.getEndTime() + ":00";
            } else {
                endDateTime = startDateTime;
            }
            start = new GoogleCalendarDateTime(null, startDateTime, timeZone);
            end = new GoogleCalendarDateTime(null, endDateTime, timeZone);
        }

        return new GoogleCalendarEventRequest(summary, description, event.getLocation(), start, end);
    }
}
