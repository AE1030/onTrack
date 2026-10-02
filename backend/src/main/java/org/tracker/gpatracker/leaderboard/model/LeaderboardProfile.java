package org.tracker.gpatracker.leaderboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Filter;
import org.tracker.gpatracker.tenancy.OwnerFilter;
import org.tracker.gpatracker.tenancy.UserOwnedEntity;

import java.time.Instant;

/**
 * A student's identity on the board, and the record that they opted in knowingly.
 *
 * <p>The handle lives here rather than on {@code Users.username} because username predates the
 * leaderboard: it has no uniqueness contract, no profanity screen, and no rate limit, and
 * retrofitting all three onto the auth flow would change what an existing account is allowed to be
 * called. Only the handle is published; everything else on this row is the consent trail, which is
 * why the table is filtered even though the name it holds is public.
 *
 * <p>One row per student, not per season — a handle is a property of the student. The consent
 * timestamps are what the board needs to show that a disclosure of derived grade data was
 * deliberate.
 */
@Entity
// Must be declared here, on the concrete entity: Hibernate does not inherit @Filter from a
// @MappedSuperclass, and omitting it leaves the table silently unfiltered.
@Filter(name = OwnerFilter.NAME)
@Table(
        name = "leaderboard_profile",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_leaderboard_profile_student",
                columnNames = "student_id"
        )
)
public class LeaderboardProfile extends UserOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // The owner lives in UserOwnedEntity.ownerId, mapped to the same student_id column.

    /**
     * Uniqueness is enforced case-insensitively by a functional index on {@code lower(handle)},
     * not by this mapping — "Ahmed" and "ahmed" are the same handle to anyone reading the board.
     */
    @Column(name = "handle", nullable = false, length = 24)
    private String handle;

    @Column(name = "opted_in_at")
    private Instant optedInAt;

    /** When the rules were shown and accepted, so a later enforcement is not arguable. */
    @Column(name = "rules_accepted_at")
    private Instant rulesAcceptedAt;

    /**
     * Last handle change, or null if it has never been renamed. One change per season: unlimited
     * changes would let someone shed recognition after a bad season, and this survives a withdraw
     * and rejoin so cycling cannot reset the limit either.
     */
    @Column(name = "handle_changed_at")
    private Instant handleChangedAt;

    /**
     * The face the student picked, or null if they never picked one and the client should derive
     * it from the handle.
     */
    @Convert(converter = LeaderboardAvatarConverter.class)
    @Column(name = "avatar", length = 32)
    private LeaderboardAvatar avatar;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getHandle() {
        return handle;
    }

    public void setHandle(String handle) {
        this.handle = handle;
    }

    public Instant getOptedInAt() {
        return optedInAt;
    }

    public void setOptedInAt(Instant optedInAt) {
        this.optedInAt = optedInAt;
    }

    public Instant getRulesAcceptedAt() {
        return rulesAcceptedAt;
    }

    public void setRulesAcceptedAt(Instant rulesAcceptedAt) {
        this.rulesAcceptedAt = rulesAcceptedAt;
    }

    public Instant getHandleChangedAt() {
        return handleChangedAt;
    }

    public void setHandleChangedAt(Instant handleChangedAt) {
        this.handleChangedAt = handleChangedAt;
    }

    public LeaderboardAvatar getAvatar() {
        return avatar;
    }

    public void setAvatar(LeaderboardAvatar avatar) {
        this.avatar = avatar;
    }
}
