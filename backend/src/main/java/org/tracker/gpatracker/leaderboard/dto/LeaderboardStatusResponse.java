package org.tracker.gpatracker.leaderboard.dto;

import org.tracker.gpatracker.leaderboard.model.EntryStatus;
import org.tracker.gpatracker.leaderboard.model.LeaderboardAvatar;
import org.tracker.gpatracker.leaderboard.model.ScoreMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Where the caller stands with the board: joined or not, under which terms, and with what left to
 * spend.
 *
 * <p>Cheap enough to call on every open of the leaderboard screen — a couple of indexed row reads
 * and no recomputation. The client uses it to decide whether to show the board or the join gate.
 *
 * @param joined              whether an entry exists for this season, in any status
 * @param status              null when never joined
 * @param canChangeHandle     false once the one rename per season has been used
 * @param transcriptSubmitted whether a baseline exists to set a target against yet
 * @param ceiling             the best score this season's frozen target can still produce
 * @param avatar              the face the student picked; null when never joined or never chosen
 * @param rank                position after the most recent run; null until the job has ranked them
 * @param rankDelta           places moved since the run before, null before there are two runs
 * @param history             oldest first, at most seven points, for the sparkline to draw
 */
public record LeaderboardStatusResponse(
        String season,
        boolean joined,
        EntryStatus status,
        String handle,
        ScoreMode mode,
        BigDecimal score,
        BigDecimal ceiling,
        boolean canChangeHandle,
        boolean transcriptSubmitted,
        Instant computedAt,
        LeaderboardAvatar avatar,
        Integer rank,
        Integer rankDelta,
        List<RankSampleDTO> history) {

    public static LeaderboardStatusResponse notJoined(String season, boolean transcriptSubmitted) {
        return new LeaderboardStatusResponse(season, false, null, null, null, null, null,
                false, transcriptSubmitted, null, null, null, null, List.of());
    }
}
