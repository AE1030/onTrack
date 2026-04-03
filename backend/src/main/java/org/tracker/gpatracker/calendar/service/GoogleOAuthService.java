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
import org.tracker.gpatracker.calendar.dto.GoogleTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Service
public class GoogleOAuthService {

    private static final Logger logger = LoggerFactory.getLogger(GoogleOAuthService.class);

    private final GoogleOAuthProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GoogleOAuthService(GoogleOAuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    public GoogleTokens exchangeCode(String code) {
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

        String email = fetchUserEmail(tokenResponse.getAccessToken());
        if (email == null && tokenResponse.getIdToken() != null) {
            email = extractEmailFromIdToken(tokenResponse.getIdToken());
        }

        Instant expiresAt = Instant.now().plusSeconds(tokenResponse.getExpiresIn());
        return new GoogleTokens(
                tokenResponse.getAccessToken(),
                tokenResponse.getRefreshToken(),
                expiresAt,
                email

        );
    }

    public GoogleTokens refreshAccessToken(String refreshToken) {
        String clientId = properties.getClientId();
        String clientSecret = properties.getClientSecret();
        logger.info("Refreshing Google access token with clientId={}", clientId);
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
            throw ex;
        }

        if (tokenResponse == null || tokenResponse.getAccessToken() == null) {
            logger.warn("Google token refresh missing access token");
            throw new IllegalStateException("Google token refresh missing access token");
        }

        Instant expiresAt = Instant.now().plusSeconds(tokenResponse.getExpiresIn());
        return new GoogleTokens(tokenResponse.getAccessToken(), refreshToken, expiresAt, null);
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
