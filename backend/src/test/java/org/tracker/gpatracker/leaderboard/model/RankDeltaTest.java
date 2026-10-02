package org.tracker.gpatracker.leaderboard.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Movement on the board.
 *
 * <p>Worth its own test because the arithmetic reads backwards: ranks count down, so an
 * improvement is the previous number minus the current one. Getting the sign wrong would point
 * every arrow on the dashboard the wrong way, and nothing else would fail.
 */
class RankDeltaTest {

    private static LeaderboardEntry entry(Integer previousRank, Integer rank) {
        LeaderboardEntry entry = new LeaderboardEntry();
        entry.setPreviousRank(previousRank);
        entry.setRank(rank);
        return entry;
    }

    @Test
    @DisplayName("climbing the board is a positive delta")
    void climbingIsPositive() {
        assertThat(entry(12, 9).rankDelta()).isEqualTo(3);
    }

    @Test
    @DisplayName("sliding down the board is a negative delta")
    void slidingIsNegative() {
        assertThat(entry(4, 6).rankDelta()).isEqualTo(-2);
    }

    @Test
    @DisplayName("holding position is zero, which is not the same as unknown")
    void holdingIsZero() {
        assertThat(entry(7, 7).rankDelta()).isZero();
    }

    /**
     * The distinction the chip depends on. Null means "no earlier run to compare against" and the
     * client shows "New"; zero means "we compared, and nothing moved". Collapsing the two would
     * tell a student who just joined that they had held their position.
     */
    @Test
    @DisplayName("a first run has no delta rather than a delta of zero")
    void firstRunHasNoDelta() {
        assertThat(entry(null, 5).rankDelta()).isNull();
    }

    @Test
    @DisplayName("an entry the job has never ranked has no delta")
    void neverRankedHasNoDelta() {
        assertThat(entry(null, null).rankDelta()).isNull();
        assertThat(entry(3, null).rankDelta()).isNull();
    }
}
