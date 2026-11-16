package org.tracker.gpatracker.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserFacingMessageTest {

    @Test
    @DisplayName("a message onTrack wrote itself is passed on")
    void ownMessage() {
        assertThat(UserFacingMessage.of(new IllegalArgumentException("Email is required"), "fallback"))
                .isEqualTo("Email is required");
    }

    @Test
    @DisplayName("a JDK or library message gets the fallback")
    void libraryMessage() {
        IllegalArgumentException fromJdk = null;
        try {
            UUID.fromString("not-a-uuid");
        } catch (IllegalArgumentException e) {
            fromJdk = e;
        }
        assertThat(fromJdk).isNotNull();
        assertThat(UserFacingMessage.of(fromJdk, "fallback")).isEqualTo("fallback");
    }

    @Test
    @DisplayName("a blank message or an empty stack trace gets the fallback")
    void unknownOrigin() {
        assertThat(UserFacingMessage.of(new IllegalArgumentException(), "fallback")).isEqualTo("fallback");
        IllegalArgumentException noTrace = new IllegalArgumentException("x");
        noTrace.setStackTrace(new StackTraceElement[0]);
        assertThat(UserFacingMessage.of(noTrace, "fallback")).isEqualTo("fallback");
    }
}
