package org.tracker.gpatracker.calendar.service;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GoogleUserInfoResponse {
    private String email;

    public GoogleUserInfoResponse() {
        // Required by Jackson for JSON deserialization
    }

    public String getEmail() {
        return email;
    }

    @JsonProperty("email")
    public void setEmail(String email) {
        this.email = email;
    }
}
