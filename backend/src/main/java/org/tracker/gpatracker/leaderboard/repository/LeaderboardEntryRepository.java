package org.tracker.gpatracker.leaderboard.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.tracker.gpatracker.leaderboard.model.EntryStatus;
import org.tracker.gpatracker.leaderboard.model.LeaderboardEntry;

import java.util.List;
import java.util.Optional;

/**
 * The public half of the leaderboard.
 *
 * <p>Extends {@code JpaRepository} rather than {@code UserScopedRepository}, which is the one place
 * in this codebase where that is a deliberate choice rather than an oversight: the board is meant
 * to be read by everyone. {@code LeaderboardEntry} does not implement {@code UserOwned}, so
 * {@code TenantMappingTest} does not require the scoped interface here — see the class javadoc on
 * the entity for why nothing on it is private.
 */
public interface LeaderboardEntryRepository extends JpaRepository<LeaderboardEntry, Long> {

    /**
     * The board. Ordered by score and then by handle, so two identical scores land in a stable
     * order instead of shuffling between page loads.
     */
    List<LeaderboardEntry> findBySeasonAndStatusOrderByScoreDescHandleAsc(String season,
                                                                          EntryStatus status,
                                                                          Pageable pageable);

    /** Every ranked row for a season, unpaged — used to work out where one student sits. */
    List<LeaderboardEntry> findBySeasonAndStatusOrderByScoreDescHandleAsc(String season, EntryStatus status);

    Optional<LeaderboardEntry> findByStudentIdAndSeason(Long studentId, String season);

    long countBySeasonAndStatus(String season, EntryStatus status);
}
