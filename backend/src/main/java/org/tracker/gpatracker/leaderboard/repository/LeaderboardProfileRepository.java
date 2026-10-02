package org.tracker.gpatracker.leaderboard.repository;

import org.tracker.gpatracker.leaderboard.model.LeaderboardProfile;
import org.tracker.gpatracker.tenancy.UserScopedRepository;

import java.util.Optional;

public interface LeaderboardProfileRepository extends UserScopedRepository<LeaderboardProfile, Long> {

    Optional<LeaderboardProfile> findByOwnerId(Long studentId);

    /**
     * Handle uniqueness, checked case-insensitively to match the functional unique index.
     *
     * <p>Only meaningful under {@code TenantScope.unfiltered}: with the owner filter enabled this
     * can only ever see the caller's own row, so it would report every handle in the system as
     * free. The service that calls it is responsible for the unfiltered scope, and the database
     * index is the backstop if it ever forgets.
     */
    boolean existsByHandleIgnoreCase(String handle);
}
