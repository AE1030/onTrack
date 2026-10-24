package org.tracker.gpatracker.calendar.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.servlet.ModelAndView;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.calendar.config.GoogleOAuthProperties;
import org.tracker.gpatracker.calendar.dto.LinkTokenPayload;
import org.tracker.gpatracker.calendar.dto.OAuthTokens;
import org.tracker.gpatracker.calendar.event.CalendarProjectionUpdatedEvent;
import org.tracker.gpatracker.calendar.model.CalendarProvider;
import org.tracker.gpatracker.calendar.service.CalendarProjectionService;
import org.tracker.gpatracker.calendar.service.CalendarSyncService;
import org.tracker.gpatracker.calendar.service.GoogleOAuthService;
import org.tracker.gpatracker.calendar.service.LinkTokenService;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleCalendarControllerCallbackTest {

    private static final String SUCCESS = "/api/calendar/google/success";
    private static final String FAILURE = "/api/calendar/google/failure";

    private LinkTokenService linkTokenService;
    private GoogleOAuthService googleOAuthService;
    private CalendarSyncService calendarSyncService;
    private StudentService studentService;
    private CalendarProjectionService calendarProjectionService;
    private ApplicationEventPublisher eventPublisher;
    private GoogleCalendarController controller;

    @BeforeEach
    void setUp() {
        linkTokenService = mock(LinkTokenService.class);
        googleOAuthService = mock(GoogleOAuthService.class);
        calendarSyncService = mock(CalendarSyncService.class);
        studentService = mock(StudentService.class);
        calendarProjectionService = mock(CalendarProjectionService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        MockEnvironment env = new MockEnvironment()
                .withProperty("calendar.oauth.success-redirect", SUCCESS)
                .withProperty("calendar.oauth.failure-redirect", FAILURE);
        controller = new GoogleCalendarController(new GoogleOAuthProperties(), linkTokenService,
                googleOAuthService, calendarSyncService, studentService, calendarProjectionService, eventPublisher, env);
    }

    @Test
    @DisplayName("a completed connect lands on the success page")
    void success() {
        validState();
        when(googleOAuthService.exchangeCode("code")).thenReturn(tokens());
        when(studentService.findStudentIdByUserId(7L)).thenReturn(70L);

        assertThat(controller.callback("code", "state", null).getUrl()).isEqualTo(SUCCESS);
        verify(calendarSyncService).upsertCalendarAccount(eq(70L), eq(CalendarProvider.GOOGLE), any());
        // The first sync is started here rather than by the app.
        verify(eventPublisher).publishEvent(new CalendarProjectionUpdatedEvent(70L));
    }

    @Test
    @DisplayName("failing to start the first sync still lands on the success page, since the account is connected")
    void firstSyncFailureIsStillSuccess() {
        validState();
        when(googleOAuthService.exchangeCode("code")).thenReturn(tokens());
        when(studentService.findStudentIdByUserId(7L)).thenReturn(70L);
        doThrow(new RuntimeException("executor queue full")).when(eventPublisher).publishEvent(any(Object.class));

        assertThat(controller.callback("code", "state", null).getUrl()).isEqualTo(SUCCESS);
    }

    @Test
    @DisplayName("cancelling on Google's consent screen lands on the failure page as cancelled")
    void cancelled() {
        assertThat(controller.callback(null, "state", "access_denied").getUrl())
                .isEqualTo(FAILURE + "?reason=cancelled");
        verify(googleOAuthService, never()).exchangeCode(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("an expired or forged state token lands on the failure page as expired")
    void badState() {
        when(linkTokenService.parseStateToken("state")).thenThrow(new RuntimeException("expired"));

        assertThat(controller.callback("code", "state", null).getUrl()).isEqualTo(FAILURE + "?reason=expired");
    }

    @Test
    @DisplayName("a failed code exchange, a missing student or a failed save land on the failure page")
    void otherFailures() {
        validState();
        when(googleOAuthService.exchangeCode("code")).thenThrow(new RuntimeException("400"));
        assertThat(controller.callback("code", "state", null).getUrl()).isEqualTo(FAILURE + "?reason=error");

        doReturn(tokens()).when(googleOAuthService).exchangeCode("code");
        when(studentService.findStudentIdByUserId(7L)).thenReturn(null);
        assertThat(controller.callback("code", "state", null).getUrl()).isEqualTo(FAILURE + "?reason=error");

        when(studentService.findStudentIdByUserId(7L)).thenReturn(70L);
        doThrow(new RuntimeException("db down")).when(calendarSyncService)
                .upsertCalendarAccount(eq(70L), eq(CalendarProvider.GOOGLE), any());
        assertThat(controller.callback("code", "state", null).getUrl()).isEqualTo(FAILURE + "?reason=error");

        assertThat(controller.callback(null, null, null).getUrl()).isEqualTo(FAILURE + "?reason=error");
    }

    @Test
    @DisplayName("the failure page shows the message for the reason, and the generic one for anything unknown")
    void failurePageMessages() {
        ModelAndView cancelled = controller.oauthFailure("cancelled");
        assertThat(cancelled.getViewName()).isEqualTo("calendar/oauth-failure");
        assertThat(cancelled.getModel().get("title")).isEqualTo("Connection cancelled");

        assertThat(controller.oauthFailure("<script>").getModel().get("title"))
                .isEqualTo("Couldn't connect Google Calendar");
        assertThat(controller.oauthFailure(null).getModel().get("title"))
                .isEqualTo("Couldn't connect Google Calendar");
    }

    private void validState() {
        when(linkTokenService.parseStateToken("state")).thenReturn(new LinkTokenPayload(7L, CalendarProvider.GOOGLE));
    }

    private static OAuthTokens tokens() {
        return new OAuthTokens("access", "refresh", Instant.now().plusSeconds(3600), "a@gmail.com");
    }
}
