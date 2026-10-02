package org.tracker.gpatracker.leaderboard.dto;

import java.time.Instant;
import java.util.List;

/**
 * The board as one screen: the top rows, plus the caller's own standing.
 *
 * <p>{@code me} is returned separately and always, even when the caller is nowhere near the top.
 * A student who cannot see their own number on a board built to motivate them has been given a
 * reason to stop looking, which is the failure mode this feature most has to avoid.
 *
 * @param computedAt when the ranking job last rewrote these rows; null before it has ever run
 * @param entrants   how many students are ranked this season, so a rank reads as "12th of 40"
 */
public record LeaderboardResponse(
        String season,
        List<LeaderboardRowDTO> rows,
        LeaderboardRowDTO me,
        long entrants,
        Instant computedAt) {
}
