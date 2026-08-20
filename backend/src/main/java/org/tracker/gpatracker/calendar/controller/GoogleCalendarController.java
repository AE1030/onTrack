package org.tracker.gpatracker.calendar.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
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
import org.tracker.gpatracker.calendar.config.GoogleOAuthProperties;
import org.tracker.gpatracker.calendar.dto.CalendarConnectRequest;
import org.tracker.gpatracker.calendar.dto.GoogleTokens;
import org.tracker.gpatracker.calendar.dto.LinkTokenPayload;
import org.tracker.gpatracker.calendar.model.CalendarProvider;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.calendar.service.GoogleCalendarSyncService;
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
    private final GoogleCalendarSyncService googleCalendarSyncService;
    private final StudentService studentService;
    private final String successRedirectUrl;

    public GoogleCalendarController(
            GoogleOAuthProperties googleOAuthProperties,
            LinkTokenService linkTokenService,
            GoogleOAuthService googleOAuthService,
            GoogleCalendarSyncService googleCalendarSyncService,
            StudentService studentService,
            org.springframework.core.env.Environment environment
    ) {
        this.googleOAuthProperties = googleOAuthProperties;
        this.linkTokenService = linkTokenService;
        this.googleOAuthService = googleOAuthService;
        this.googleCalendarSyncService = googleCalendarSyncService;
        this.studentService = studentService;
        this.successRedirectUrl = environment.getProperty("calendar.oauth.success-redirect", "/");
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

    @GetMapping("/callback")
    public RedirectView callback(
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            HttpServletResponse response
    ) {
        LinkTokenPayload payload;
        try {
            payload = linkTokenService.parseStateToken(state);
        } catch (RuntimeException ex) {
            logger.warn("Google callback state validation failed: {}", ex.getMessage());
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return new RedirectView(successRedirectUrl);
        }
        if (payload.provider() != CalendarProvider.GOOGLE) {
            logger.warn("Google callback provider mismatch: {}", payload.provider());
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return new RedirectView(successRedirectUrl);
        }

        GoogleTokens tokens;
        try {
            tokens = googleOAuthService.exchangeCode(code);
        } catch (RuntimeException ex) {
            logger.error("Google token exchange failed for user {}: {}", payload.userId(), ex.getMessage());
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return new RedirectView(successRedirectUrl);
        }
        // Pre-JWT path: no Authorization header, so nothing has bound a tenant. Identity comes
        // from the signed state token, which is what makes this a legitimate escape hatch rather
        // than a hole — the client cannot forge payload.userId().
        Long studentId = studentService.findStudentIdByUserId(payload.userId());
        if (studentId == null) {
            logger.warn("Google callback for user {} that has no student account", payload.userId());
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return new RedirectView(successRedirectUrl);
        }
        UserContext.runAs(payload.userId(), studentId,
                () -> googleCalendarSyncService.upsertCalendarAccount(studentId, tokens));
        logger.info("Google calendar connected for user {}", payload.userId());
        return new RedirectView(successRedirectUrl);
    }

    @GetMapping("/success")
    public ModelAndView oauthSuccess() {
        return new ModelAndView("calendar/oauth-success");
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, String>> sync(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CalendarConnectRequest request
    ) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String email = request.getCalendarEmail();
        Long userId = principal.getId();
        Long studentId = studentService.getStudentID();

        if (!googleCalendarSyncService.isAccountConnectedForEmail(studentId, email)) {
            String state = linkTokenService.createStateToken(userId, CalendarProvider.GOOGLE);
            String authorizationUrl = buildAuthorizationUrl(state, email);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("action", "connect", AUTHORIZATION_URL_KEY, authorizationUrl));
        }

        try {
            googleCalendarSyncService.sync(studentId);
        } catch (IllegalStateException e) {
            String state = linkTokenService.createStateToken(userId, CalendarProvider.GOOGLE);
            String authorizationUrl = buildAuthorizationUrl(state, email);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("action", "connect", AUTHORIZATION_URL_KEY, authorizationUrl));
        }
        return ResponseEntity.ok(Map.of("status", "synced"));
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
