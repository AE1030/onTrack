package org.tracker.gpatracker.security.dto;

/** Body of a successful /login or /refresh. */
public record AuthTokens(String accessToken, String refreshToken) {
}
