package org.tracker.gpatracker.calendar.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.calendar.config.GoogleOAuthProperties;
import org.tracker.gpatracker.calendar.dto.CalendarConnectRequest;
import org.tracker.gpatracker.calendar.exception.CalendarReconnectRequiredException;
import org.tracker.gpatracker.calendar.model.CalendarEvents;
import org.tracker.gpatracker.calendar.model.CalendarEvents.InternalCalendarEvent;
import org.tracker.gpatracker.calendar.model.CalendarProvider;
import org.tracker.gpatracker.calendar.service.CalendarProjectionService;
import org.tracker.gpatracker.calendar.service.CalendarSyncService;
import org.tracker.gpatracker.calendar.service.GoogleOAuthService;
import org.tracker.gpatracker.calendar.service.LinkTokenService;
import org.tracker.gpatracker.security.model.UserPrincipal;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleCalendarControllerSyncTest {

    private static final Long STUDENT = 70L;
    private static final String EMAIL = "a@gmail.com";

    private CalendarSyncService calendarSyncService;
    private CalendarProjectionService calendarProjectionService;
    private GoogleCalendarController controller;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        calendarSyncService = mock(CalendarSyncService.class);
        calendarProjectionService = mock(CalendarProjectionService.class);
        StudentService studentService = mock(StudentService.class);
        when(studentService.getStudentID()).thenReturn(STUDENT);
        LinkTokenService linkTokenService = mock(LinkTokenService.class);
        when(linkTokenService.createStateToken(any(), any())).thenReturn("state");

        GoogleOAuthProperties properties = new GoogleOAuthProperties();
        properties.setClientId("client");
        properties.setRedirectUri("http://localhost:8080/api/calendar/google/callback");
        properties.setAuthorizationUri("https://accounts.google.com/o/oauth2/v2/auth");
        properties.setScopes(List.of("https://www.googleapis.com/auth/calendar.events"));

        controller = new GoogleCalendarController(properties, linkTokenService, mock(GoogleOAuthService.class),
                calendarSyncService, studentService, calendarProjectionService,
                mock(ApplicationEventPublisher.class), new MockEnvironment());

        principal = mock(UserPrincipal.class);
        when(principal.getId()).thenReturn(7L);
        when(calendarSyncService.isAccountConnectedForEmail(STUDENT, CalendarProvider.GOOGLE, EMAIL)).thenReturn(true);
    }

    @Test
    @DisplayName("a successful sync reports how many events the calendar now holds")
    void reportsEventCount() {
        projectionWith(2);

        ResponseEntity<Map<String, Object>> res = controller.sync(principal, request());

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("status", "synced").containsEntry("eventCount", 2);
    }

    @Test
    @DisplayName("nothing to export is a 200 with a count of zero, not an error")
    void emptyIsZero() {
        projectionWith(0);

        assertThat(controller.sync(principal, request()).getBody()).containsEntry("eventCount", 0);
    }

    @Test
    @DisplayName("a revoked or missing account answers with the connect prompt")
    void reconnectIsConflict() {
        doThrow(new CalendarReconnectRequiredException("revoked"))
                .when(calendarSyncService).sync(STUDENT, CalendarProvider.GOOGLE);

        ResponseEntity<Map<String, Object>> res = controller.sync(principal, request());

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody()).containsEntry("action", "connect").containsKey("authorizationUrl");
    }

    @Test
    @DisplayName("any other IllegalStateException is not turned into a connect prompt")
    void otherFailuresAreNotConflict() {
        doThrow(new IllegalStateException("No calendar strategy registered for GOOGLE"))
                .when(calendarSyncService).sync(STUDENT, CalendarProvider.GOOGLE);

        // Left to GlobalExceptionHandler, which answers 500 with a generic message.
        assertThatThrownBy(() -> controller.sync(principal, request()))
                .isInstanceOf(IllegalStateException.class);
    }

    private void projectionWith(int count) {
        CalendarEvents projection = new CalendarEvents();
        projection.setEvents(java.util.stream.Stream.generate(InternalCalendarEvent::new).limit(count).toList());
        when(calendarProjectionService.getCalendarEvents(STUDENT)).thenReturn(Optional.of(projection));
    }

    private static CalendarConnectRequest request() {
        CalendarConnectRequest request = new CalendarConnectRequest();
        request.setCalendarEmail(EMAIL);
        return request;
    }
}
