package org.tracker.gpatracker.calendar.service;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GoogleTokenResponse {
    private String accessToken;
    private String refreshToken;
    private long expiresIn;
    private String idToken;
    /** Space-separated scopes the student actually granted, which can be fewer than requested. */
    private String scope;

    public GoogleTokenResponse() {
        // Required by Jackson for JSON deserialization
    }

    public String getAccessToken() {
        return accessToken;
    }

    @JsonProperty("access_token")
    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    @JsonProperty("refresh_token")
    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    @JsonProperty("expires_in")
    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public String getIdToken() {
        return idToken;
    }

    @JsonProperty("id_token")
    public void setIdToken(String idToken) {
        this.idToken = idToken;
    }

    public String getScope() {
        return scope;
    }

    @JsonProperty("scope")
    public void setScope(String scope) {
        this.scope = scope;
    }
}
