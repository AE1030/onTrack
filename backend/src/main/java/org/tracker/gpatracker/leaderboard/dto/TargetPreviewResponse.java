package org.tracker.gpatracker.leaderboard.dto;

import org.tracker.gpatracker.leaderboard.model.ScoreMode;

import java.math.BigDecimal;

/**
 * What a candidate target is worth, recomputed as the student moves it.
 *
 * <p>This exists because the reward for reaching is only motivating if it is visible at the moment
 * of choosing. A student who sets their target without seeing the ceiling move has no reason to
 * believe the ambition bonus is real.
 *
 * @param mode     which mode this target selects — the choice is made here, not later
 * @param ceiling  the best score this baseline and target can produce, before behaviour damping
 * @param ambition how much of their available reach the target claims, 0 to 1
 */
public record TargetPreviewResponse(
        BigDecimal baselineGpa12,
        BigDecimal targetGpa12,
        ScoreMode mode,
        BigDecimal ceiling,
        BigDecimal ambition) {
}
