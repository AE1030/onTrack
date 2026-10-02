package org.tracker.gpatracker.leaderboard.dto;

import java.math.BigDecimal;

/**
 * What the student's transcript established, handed back so they can choose a target against it.
 *
 * @param baselineGpa12   the parsed transcript GPA — zero is a real answer for a first-year
 * @param headroom        how much of the 12-point scale is left above them
 * @param growthAvailable false above 11.0, where a growth target is arithmetically impossible
 * @param minGrowthTarget the lowest target that still counts as growth, or null when it is out of
 *                        reach — the client shows this as the boundary on its target slider
 * @param frozen          true when this season's baseline was already set and this upload changed
 *                        nothing; the client should say so rather than implying a fresh start
 */
public record TranscriptBaselineResponse(
        BigDecimal baselineGpa12,
        BigDecimal headroom,
        boolean growthAvailable,
        BigDecimal minGrowthTarget,
        boolean frozen) {
}
