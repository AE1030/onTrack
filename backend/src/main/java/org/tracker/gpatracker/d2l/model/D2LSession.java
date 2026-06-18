package org.tracker.gpatracker.d2l.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A harvested D2L Brightspace session for a single onTrack user.
 *
 * <p>Replaces the spec's in-memory {@code SessionStore} singleton: instead of one shared cookie jar,
 * each user owns a persisted, encrypted session. The full cookie jar (short-lived auth token plus the
 * long-lived ~24h session cookie) and the XSRF token are captured once by a WebView on the user's
 * device; the backend later refreshes the short-lived token over plain HTTP using the long-lived cookie.
 */
@Entity
@Table(
        name = "d2l_session",
        indexes = {
                @Index(name = "idx_d2l_session_user", columnList = "user_id", unique = true)
        }
)
public class D2LSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Full {@code k=v; k=v} cookie jar, encrypted at rest. */
    @Convert(converter = D2LCredentialEncryptionConverter.class)
    @Column(name = "cookie_header", nullable = false, length = 8192)
    private String cookieHeader;

    /** Brightspace XSRF token sent as the {@code X-Csrf-Token} header, encrypted at rest. */
    @Convert(converter = D2LCredentialEncryptionConverter.class)
    @Column(name = "xsrf_token", nullable = false, length = 2048)
    private String xsrfToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_refresh_at")
    private Instant lastRefreshAt;

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getCookieHeader() {
        return cookieHeader;
    }

    public void setCookieHeader(String cookieHeader) {
        this.cookieHeader = cookieHeader;
    }

    public String getXsrfToken() {
        return xsrfToken;
    }

    public void setXsrfToken(String xsrfToken) {
        this.xsrfToken = xsrfToken;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getLastRefreshAt() {
        return lastRefreshAt;
    }

    public void setLastRefreshAt(Instant lastRefreshAt) {
        this.lastRefreshAt = lastRefreshAt;
    }
}
