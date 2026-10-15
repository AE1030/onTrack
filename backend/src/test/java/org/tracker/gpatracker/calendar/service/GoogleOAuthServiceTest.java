package org.tracker.gpatracker.calendar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.tracker.gpatracker.calendar.config.GoogleOAuthProperties;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleOAuthServiceTest {

    private final GoogleOAuthService service = new GoogleOAuthService(new GoogleOAuthProperties(), new ObjectMapper());

    @Test
    @DisplayName("a 400 with invalid_grant is a revoked token")
    void invalidGrantIsRevoked() {
        assertThat(service.isInvalidGrant(clientError(HttpStatus.BAD_REQUEST,
                "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}")))
                .isTrue();
    }

    @Test
    @DisplayName("other 400s, other statuses and unreadable bodies are not")
    void everythingElseIsNot() {
        assertThat(service.isInvalidGrant(clientError(HttpStatus.BAD_REQUEST, "{\"error\":\"invalid_client\"}"))).isFalse();
        assertThat(service.isInvalidGrant(clientError(HttpStatus.BAD_REQUEST, "<html>Bad Request</html>"))).isFalse();
        assertThat(service.isInvalidGrant(clientError(HttpStatus.UNAUTHORIZED, "{\"error\":\"invalid_grant\"}"))).isFalse();
        assertThat(service.isInvalidGrant(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8))).isFalse();
    }

    private static RestClientResponseException clientError(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
