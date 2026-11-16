package org.tracker.gpatracker.calendar.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.tracker.gpatracker.calendar.config.GoogleOAuthProperties;
import org.tracker.gpatracker.calendar.dto.OAuthTokens;
import org.tracker.gpatracker.calendar.exception.CalendarAuthorizationRevokedException;
import org.tracker.gpatracker.calendar.exception.CalendarScopeNotGrantedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GoogleOAuthService {

    private static final Logger logger = LoggerFactory.getLogger(GoogleOAuthService.class);

    /** Every Google Calendar scope starts with this. The bare value is full calendar access. */
    static final String CALENDAR_SCOPE_PREFIX = "https://www.googleapis.com/auth/calendar";

    private final GoogleOAuthProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GoogleOAuthService(GoogleOAuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    public OAuthTokens exchangeCode(String code) {
        String clientId = properties.getClientId();
        String clientSecret = properties.getClientSecret();
        String redirectUri = properties.getRedirectUri();

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");

        GoogleTokenResponse tokenResponse;
        try {
            tokenResponse = restClient.post()
                    .uri(properties.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(GoogleTokenResponse.class);
        } catch (RestClientResponseException ex) {
            throw ex;
        }

        if (tokenResponse == null || tokenResponse.getAccessToken() == null) {
            throw new IllegalStateException("Google token response missing access token");
        }

        if (!grantsCalendarAccess(tokenResponse.getScope(), properties.getScopes())) {
            // Give the useless grant back rather than leave onTrack listed in the student's Google
            // account with nothing it can do. Best effort: the student is told either way.
            try {
                revoke(tokenResponse.getRefreshToken() != null
                        ? tokenResponse.getRefreshToken() : tokenResponse.getAccessToken());
            } catch (RuntimeException ex) {
                logger.warn("Revoking a Google grant without calendar access failed: {}", ex.getMessage());
            }
            throw new CalendarScopeNotGrantedException("Google consent completed without calendar access");
        }

        String email = fetchUserEmail(tokenResponse.getAccessToken());
        if (email == null && tokenResponse.getIdToken() != null) {
            email = extractEmailFromIdToken(tokenResponse.getIdToken());
        }

        Instant expiresAt = Instant.now().plusSeconds(tokenResponse.getExpiresIn());
        return new OAuthTokens(
                tokenResponse.getAccessToken(),
                tokenResponse.getRefreshToken(),
                expiresAt,
                email

        );
    }

    public OAuthTokens refreshAccessToken(String refreshToken) {
        String clientId = properties.getClientId();
        String clientSecret = properties.getClientSecret();
        logger.info("Refreshing Google access token");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");

        GoogleTokenResponse tokenResponse;
        try {
            tokenResponse = restClient.post()
                    .uri(properties.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(GoogleTokenResponse.class);
        } catch (RestClientResponseException ex) {
            if (isInvalidGrant(ex)) {
                throw new CalendarAuthorizationRevokedException("Google refresh token revoked or expired", ex);
            }
            throw ex;
        }

        if (tokenResponse == null || tokenResponse.getAccessToken() == null) {
            logger.warn("Google token refresh missing access token");
            throw new IllegalStateException("Google token refresh missing access token");
        }

        Instant expiresAt = Instant.now().plusSeconds(tokenResponse.getExpiresIn());
        return new OAuthTokens(tokenResponse.getAccessToken(), refreshToken, expiresAt, null);
    }

    /**
     * Revokes the grant behind a refresh token, which also invalidates its access tokens and removes
     * onTrack from the student's "Third-party apps" list in their Google Account. Google answers 400
     * for a token that is already invalid, which is the outcome we wanted, so that is not an error.
     */
    public void revoke(String refreshToken) {
        if (refreshToken == null) {
            return;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", refreshToken);
        try {
            restClient.post()
                    .uri(properties.getRevokeUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 400) {
                throw ex;
            }
        }
    }

    /**
     * Google answers a revoked or expired refresh token with 400 and {@code "error": "invalid_grant"}.
     * Other 400s (a bad client id, say) are our misconfiguration, not the student's token.
     */
    boolean isInvalidGrant(RestClientResponseException ex) {
        if (ex.getStatusCode().value() != 400) {
            return false;
        }
        try {
            return "invalid_grant".equals(objectMapper.readTree(ex.getResponseBodyAsString()).path("error").asText());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Whether the scopes Google granted cover every calendar scope we asked for. The full
     * {@code calendar} scope covers any narrower one, since Google may return it for a student who
     * granted it to this client before.
     *
     * <p>Google documents {@code scope} as always present on a code exchange. If it is missing we
     * cannot tell, so the connection goes ahead and a warning is logged rather than every student
     * being turned away over a response-format change.
     */
    static boolean grantsCalendarAccess(String grantedScope, List<String> requestedScopes) {
        if (grantedScope == null) {
            logger.warn("Google token response has no scope field; cannot confirm calendar access");
            return true;
        }
        Set<String> granted = Arrays.stream(grantedScope.trim().split("\\s+")).collect(Collectors.toSet());
        if (granted.contains(CALENDAR_SCOPE_PREFIX)) {
            return true;
        }
        List<String> calendarScopes = requestedScopes.stream()
                .filter(s -> s.startsWith(CALENDAR_SCOPE_PREFIX))
                .toList();
        return !calendarScopes.isEmpty() && granted.containsAll(calendarScopes);
    }

    private String fetchUserEmail(String accessToken) {
        // Email is only returned when the OAuth scopes include openid/email.
        GoogleUserInfoResponse userInfo = restClient.get()
                .uri(properties.getUserInfoUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(GoogleUserInfoResponse.class);
        if (userInfo == null) {
            logger.warn("Google userinfo response missing");
        }
        return userInfo != null ? userInfo.getEmail() : null;
    }

    private String extractEmailFromIdToken(String idToken) {
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
        try {
            Map<String, Object> payload = objectMapper.readValue(decoded, new TypeReference<>() {});
            Object email = payload.get("email");
            return email != null ? email.toString() : null;
        } catch (IOException e) {
            return null;
        }
    }

}
