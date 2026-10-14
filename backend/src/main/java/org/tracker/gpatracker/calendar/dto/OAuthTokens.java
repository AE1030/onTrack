package org.tracker.gpatracker.calendar.dto;

import java.time.Instant;

public record OAuthTokens(String accessToken, String refreshToken, Instant expiresAt, String email) {
}
