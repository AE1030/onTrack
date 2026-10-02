package org.tracker.gpatracker.leaderboard.dto;

import org.tracker.gpatracker.leaderboard.model.LeaderboardAvatar;
import org.tracker.gpatracker.leaderboard.model.ScoreMode;

import java.math.BigDecimal;

/**
 * One row of the public board.
 *
 * <p>Everything here is either derived or was chosen for publication. No GPA appears — not the
 * baseline, not the target, not the projection — because those are grades, and the point of
 * ranking on a derived score is that the board never has to expose one.
 *
 * @param score up to 12,250, not 10,000: a delivered ambitious goal pays a bonus, so a client that
 *              renders this as a percentage of 10,000 will clip its own leaders
 * @param mode  shown so a 70 next to a 100 reads as a different game rather than a worse player
 * @param you   true on the caller's own row, so the client can highlight it without matching handles
 * @param avatar the face the student picked, or null for the client to derive one from the handle
 * @param rankDelta places moved since the previous run, positive meaning climbed, null when this
 *                  entry has not been through two runs yet. Not a score delta: a student can gain
 *                  points and still slide, and this is the figure that accounts for everyone else
 */
public record LeaderboardRowDTO(
        int rank,
        String handle,
        BigDecimal score,
        ScoreMode mode,
        boolean you,
        LeaderboardAvatar avatar,
        Integer rankDelta) {
}
