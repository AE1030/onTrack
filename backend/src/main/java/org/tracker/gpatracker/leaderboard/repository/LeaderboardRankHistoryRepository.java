package org.tracker.gpatracker.leaderboard.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tracker.gpatracker.leaderboard.model.LeaderboardRankHistory;

import java.time.Instant;
import java.util.List;

/**
 * Past positions on the board.
 *
 * <p>Extends {@code JpaRepository} rather than {@code UserScopedRepository}, the same deliberate
 * exception {@code LeaderboardEntryRepository} takes: see the entity for why nothing here is
 * private.
 */
public interface LeaderboardRankHistoryRepository extends JpaRepository<LeaderboardRankHistory, Long> {

    /**
     * One student's most recent samples, newest first.
     *
     * <p>The caller reverses them before drawing: a sparkline reads left to right in time order,
     * and the index is on {@code computed_at desc}, so the cheap read and the useful order are
     * opposites.
     */
    List<LeaderboardRankHistory> findByStudentIdAndSeasonOrderByComputedAtDesc(Long studentId,
                                                                               String season,
                                                                               Pageable pageable);

    /**
     * Whether some sweep has already ranked this season in the recent past.
     *
     * <p>The guard against two instances ranking the same season moments apart. Rescoring twice is
     * harmless, which is why the job tolerates it, but ranking twice is not: the second pass would
     * copy the first pass's fresh rank into {@code previous_rank}, turning a real movement into
     * "held", and would put a second point on every sparkline for one run.
     */
    boolean existsBySeasonAndComputedAtAfter(String season, Instant since);

    /**
     * Drops samples from seasons that are over.
     *
     * <p>This table only ever grows, and only the newest handful of rows per student is ever read,
     * so without this the job would accumulate rows nothing will look at again for as long as the
     * app runs.
     */
    @Modifying
    @Query("delete from LeaderboardRankHistory h where h.season <> :season")
    int deleteFromClosedSeasons(@Param("season") String season);
}
