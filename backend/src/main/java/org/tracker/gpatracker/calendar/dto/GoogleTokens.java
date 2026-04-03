package org.tracker.gpatracker.calendar.dto;

import java.time.Instant;

public record GoogleTokens(String accessToken, String refreshToken, Instant expiresAt, String email) {
}
