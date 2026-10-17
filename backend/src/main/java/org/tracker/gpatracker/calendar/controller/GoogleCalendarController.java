package org.tracker.gpatracker.calendar.controller;

import jakarta.validation.Valid;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;
import org.tracker.gpatracker.calendar.config.GoogleOAuthProperties;
import org.tracker.gpatracker.calendar.dto.CalendarConnectRequest;
import org.tracker.gpatracker.calendar.dto.OAuthTokens;
import org.tracker.gpatracker.calendar.dto.LinkTokenPayload;
import org.tracker.gpatracker.calendar.exception.CalendarReconnectRequiredException;
import org.tracker.gpatracker.calendar.exception.CalendarSyncUnavailableException;
import org.tracker.gpatracker.calendar.model.CalendarProvider;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.calendar.event.CalendarProjectionUpdatedEvent;
import org.tracker.gpatracker.calendar.service.CalendarProjectionService;
import org.tracker.gpatracker.calendar.service.CalendarSyncService;
import org.tracker.gpatracker.calendar.service.GoogleOAuthService;
import org.tracker.gpatracker.calendar.service.LinkTokenService;
import org.tracker.gpatracker.security.model.UserPrincipal;
import org.tracker.gpatracker.tenancy.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.ModelAndView;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

@RestController
@RequestMapping("/api/calendar/google")
public class GoogleCalendarController {

    private static final Logger logger = LoggerFactory.getLogger(GoogleCalendarController.class);
    private static final String AUTHORIZATION_URL_KEY = "authorizationUrl";
    private final GoogleOAuthProperties googleOAuthProperties;
    private final LinkTokenService linkTokenService;
    private final GoogleOAuthService googleOAuthService;
    private final CalendarSyncService calendarSyncService;
    private final StudentService studentService;
    private final CalendarProjectionService calendarProjectionService;
    private final ApplicationEventPublisher eventPublisher;
    private final String successRedirectUrl;
    private final String failureRedirectUrl;

    public GoogleCalendarController(
            GoogleOAuthProperties googleOAuthProperties,
            LinkTokenService linkTokenService,
            GoogleOAuthService googleOAuthService,
            CalendarSyncService calendarSyncService,
            StudentService studentService,
            CalendarProjectionService calendarProjectionService,
            ApplicationEventPublisher eventPublisher,
            org.springframework.core.env.Environment environment
    ) {
        this.googleOAuthProperties = googleOAuthProperties;
        this.linkTokenService = linkTokenService;
        this.googleOAuthService = googleOAuthService;
        this.calendarSyncService = calendarSyncService;
        this.studentService = studentService;
        this.calendarProjectionService = calendarProjectionService;
        this.eventPublisher = eventPublisher;
        this.successRedirectUrl = environment.getProperty("calendar.oauth.success-redirect", "/");
        this.failureRedirectUrl = environment.getProperty("calendar.oauth.failure-redirect", "/");
    }

    @PostMapping("/connect")
    public ResponseEntity<Map<String, String>> connect(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CalendarConnectRequest request
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String state = linkTokenService.createStateToken(principal.getId(), CalendarProvider.GOOGLE);
        String authorizationUrl = buildAuthorizationUrl(state, request.getCalendarEmail());
        return ResponseEntity.ok(Map.of(AUTHORIZATION_URL_KEY, authorizationUrl));
    }

    /**
     * Google's redirect target. Every outcome ends in a redirect to a page the student sees in the
     * browser, so failures go to the failure page rather than setting an error status: a redirect
     * replaces the status, which used to send every failure to the "Connected" page.
     */
    @GetMapping("/callback")
    public RedirectView callback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error
    ) {
        // Google sends ?error=access_denied, and no code, when the student cancels on the consent screen.
        if (error != null) {
            logger.info("Google callback returned error={}", error);
            return failure("access_denied".equals(error) ? FailureReason.CANCELLED : FailureReason.ERROR);
        }
        if (code == null || state == null) {
            logger.warn("Google callback missing code or state");
            return failure(FailureReason.ERROR);
        }

        LinkTokenPayload payload;
        try {
            payload = linkTokenService.parseStateToken(state);
        } catch (RuntimeException ex) {
            logger.warn("Google callback state validation failed: {}", ex.getMessage());
            return failure(FailureReason.EXPIRED);
        }
        if (payload.provider() != CalendarProvider.GOOGLE) {
            logger.warn("Google callback provider mismatch: {}", payload.provider());
            return failure(FailureReason.ERROR);
        }

        OAuthTokens tokens;
        try {
            tokens = googleOAuthService.exchangeCode(code);
        } catch (RuntimeException ex) {
            logger.error("Google token exchange failed for user {}: {}", payload.userId(), ex.getMessage());
            return failure(FailureReason.ERROR);
        }
        // Pre-JWT path: no Authorization header, so nothing has bound a tenant. Identity comes
        // from the signed state token, which is what makes this a legitimate escape hatch rather
        // than a hole — the client cannot forge payload.userId().
        Long studentId = studentService.findStudentIdByUserId(payload.userId());
        if (studentId == null) {
            logger.warn("Google callback for user {} that has no student account", payload.userId());
            return failure(FailureReason.ERROR);
        }
        try {
            UserContext.runAs(payload.userId(), studentId,
                    () -> calendarSyncService.upsertCalendarAccount(studentId, CalendarProvider.GOOGLE, tokens));
        } catch (RuntimeException ex) {
            logger.error("Saving Google calendar account failed for user {}: {}", payload.userId(), ex.getMessage());
            return failure(FailureReason.ERROR);
        }
        logger.info("Google calendar connected for user {}", payload.userId());

        // Start the first sync here rather than leaving it to the app. Only the server knows when
        // OAuth has finished; the app's openBrowserAsync resolves as soon as the tab opens on web
        // and Android, so a retry from there always ran before the account existed.
        //
        // Published inside runAs so TenantTaskDecorator hands this tenant to the async listener.
        // A failure here does not undo the connection, so the student still sees the success
        // page; the next table save or manual sync catches up.
        try {
            UserContext.runAs(payload.userId(), studentId,
                    () -> eventPublisher.publishEvent(new CalendarProjectionUpdatedEvent(studentId)));
        } catch (RuntimeException ex) {
            logger.warn("First Google calendar sync not started for user {}: {}", payload.userId(), ex.getMessage());
        }
        return new RedirectView(successRedirectUrl);
    }

    @GetMapping("/success")
    public ModelAndView oauthSuccess() {
        ModelAndView view = new ModelAndView("calendar/oauth-success");
        view.addObject("title", "Google Calendar Connected");
        view.addObject("message",
                "Your assessments are being added to Google Calendar. You can close this window and return to the app.");
        return view;
    }

    @GetMapping("/failure")
    public ModelAndView oauthFailure(@RequestParam(value = "reason", required = false) String reason) {
        FailureReason parsed = FailureReason.parse(reason);
        ModelAndView view = new ModelAndView("calendar/oauth-failure");
        view.addObject("title", parsed.title);
        view.addObject("message", parsed.message);
        return view;
    }

    private RedirectView failure(FailureReason reason) {
        return new RedirectView(UriComponentsBuilder.fromUriString(failureRedirectUrl)
                .queryParam("reason", reason.name().toLowerCase())
                .toUriString());
    }

    /** What the failure page tells the student. Only the name travels in the URL. */
    enum FailureReason {
        CANCELLED("Connection cancelled",
                "Google Calendar was not connected. Return to the app and try again when you're ready."),
        EXPIRED("Link expired",
                "This connection link has expired. Return to the app and start the export again."),
        ERROR("Couldn't connect Google Calendar",
                "Something went wrong while connecting. Return to the app and try again.");

        final String title;
        final String message;

        FailureReason(String title, String message) {
            this.title = title;
            this.message = message;
        }

        static FailureReason parse(String value) {
            if (value != null) {
                for (FailureReason r : values()) {
                    if (r.name().equalsIgnoreCase(value)) return r;
                }
            }
            return ERROR;
        }
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CalendarConnectRequest request
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String email = request.getCalendarEmail();
        Long userId = principal.getId();
        Long studentId = studentService.getStudentID();

        if (!calendarSyncService.isAccountConnectedForEmail(studentId, CalendarProvider.GOOGLE, email)) {
            String state = linkTokenService.createStateToken(userId, CalendarProvider.GOOGLE);
            String authorizationUrl = buildAuthorizationUrl(state, email);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("action", "connect", AUTHORIZATION_URL_KEY, authorizationUrl));
        }

        try {
            calendarSyncService.sync(studentId, CalendarProvider.GOOGLE);
        } catch (CalendarSyncUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Google Calendar is not responding right now. Please try again in a few minutes."));
        } catch (CalendarReconnectRequiredException e) {
            String state = linkTokenService.createStateToken(userId, CalendarProvider.GOOGLE);
            String authorizationUrl = buildAuthorizationUrl(state, email);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("action", "connect", AUTHORIZATION_URL_KEY, authorizationUrl));
        }
        // What the student's Google Calendar now holds, so the app can say "nothing to export"
        // instead of a success message for an empty sync.
        int eventCount = calendarProjectionService.getCalendarEvents(studentId)
                .map(e -> e.getEvents() == null ? 0 : e.getEvents().size())
                .orElse(0);
        return ResponseEntity.ok(Map.of("status", "synced", "eventCount", eventCount));
    }

    private String buildAuthorizationUrl(String state, String email) {
        StringJoiner scopeJoiner = new StringJoiner(" ");
        for (String scope : googleOAuthProperties.getScopes()) {
            scopeJoiner.add(scope);
        }

        StringBuilder url = new StringBuilder(googleOAuthProperties.getAuthorizationUri()
                + "?client_id=" + urlEncode(googleOAuthProperties.getClientId())
                + "&redirect_uri=" + urlEncode(googleOAuthProperties.getRedirectUri())
                + "&response_type=code"
                + "&scope=" + urlEncode(scopeJoiner.toString())
                + "&access_type=offline"
                + "&prompt=consent"
                + "&state=" + urlEncode(state));
        if (email != null && !email.isBlank()) {
            url.append("&login_hint=").append(urlEncode(email.trim()));
        }
        return url.toString();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
