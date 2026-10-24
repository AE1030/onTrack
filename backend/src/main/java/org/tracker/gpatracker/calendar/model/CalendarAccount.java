package org.tracker.gpatracker.calendar.model;

import org.hibernate.annotations.Filter;
import org.tracker.gpatracker.tenancy.OwnerFilter;
import org.tracker.gpatracker.tenancy.UserOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(
        name = "calendar_account",
        indexes = {
                // Repointed along with the column itself. Uniqueness has to follow the owner,
                // or it stays pinned to a column that is going away.
                @Index(name = "idx_calendar_account_student_provider", columnList = "student_id,provider", unique = true)
        }
)
@Filter(name = OwnerFilter.NAME)
public class CalendarAccount extends UserOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The owner lives in UserOwnedEntity.ownerId (column student_id). This table previously keyed
    // off user_id, a different id space, which is why it could not join the filter contract.

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CalendarProvider provider;

    @Column
    private String email;

    @Column(name = "access_token", nullable = false, length = 2048)
    private String accessToken;

    @Column(name = "refresh_token", length = 2048)
    private String refreshToken;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * The provider revoked this account's refresh token. Syncs skip it until the student
     * reconnects, which replaces the row. Kept rather than deleted so the app can say which
     * account stopped working.
     */
    @Column(name = "needs_reconnect", nullable = false)
    private boolean needsReconnect;

    public Long getId() {
        return id;
    }

    public CalendarProvider getProvider() {
        return provider;
    }

    public void setProvider(CalendarProvider provider) {
        this.provider = provider;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isNeedsReconnect() {
        return needsReconnect;
    }

    public void setNeedsReconnect(boolean needsReconnect) {
        this.needsReconnect = needsReconnect;
    }
}
