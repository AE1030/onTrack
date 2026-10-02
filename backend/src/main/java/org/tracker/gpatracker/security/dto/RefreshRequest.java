package org.tracker.gpatracker.security.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of /refresh and /logout. */
public class RefreshRequest {
    @NotBlank(message = "Refresh token is required")
    private String refreshToken;

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
