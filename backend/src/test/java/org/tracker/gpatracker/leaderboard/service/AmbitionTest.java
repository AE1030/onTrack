package org.tracker.gpatracker.leaderboard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tracker.gpatracker.leaderboard.model.ScoreMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The ambition bonus: what aiming higher is worth, and — more importantly — what it is not worth
 * before you have delivered any of it.
 */
class AmbitionTest {

    private static final double EPSILON = 1e-9;

    @Test
    @DisplayName("ambition is measured against available headroom, not a fixed span")
    void ambitionNormalisesByHeadroom() {
        // A student at 11.0 targeting 12.0 has reached as high as the scale allows. A student at
        // 4.0 targeting 8.0 has done the same thing with more room. Both claimed all of it.
        assertThat(LeaderboardScoring.ambition(11.0, 12.0)).isCloseTo(1.0, within(EPSILON));
        assertThat(LeaderboardScoring.ambition(4.0, 8.0)).isCloseTo(1.0, within(EPSILON));
    }

    @Test
    @DisplayName("being already good never caps how ambitious you are allowed to look")
    void headroomNormalisationIsWhatMakesItFair() {
        // Under a fixed 4-point span, the 11.0 student's best possible target would have scored
        // 0.25 — a quarter of the bonus, for reaching the top of the scale.
        assertThat(LeaderboardScoring.ambition(11.0, 12.0))
                .isGreaterThan(LeaderboardScoring.ambition(4.0, 5.0));
    }

    @Test
    @DisplayName("a maximal target pays nothing while nothing has been earned")
    void ambitionPaysNothingBeforeTheBaselineIsCrossed() {
        // Two students a point below their baselines, one having declared the biggest goal on the
        // board and one the smallest. Neither has delivered anything, so they score identically.
        var modest = LeaderboardScoring.score(ScoreMode.GROWTH, 8.0, 9.0, 7.0, 1.0);
        var maximal = LeaderboardScoring.score(ScoreMode.GROWTH, 8.0, 12.0, 7.0, 1.0);

        assertThat(modest).isEqualByComparingTo(maximal);
        assertThat(modest).hasToString("500.00");
    }

    @Test
    @DisplayName("two students sitting on their baselines both score exactly 1,000")
    void baselineScoresTheSameWhateverWasDeclared() {
        assertThat(LeaderboardScoring.score(ScoreMode.GROWTH, 8.0, 9.0, 8.0, 1.0)).hasToString("1000.00");
        assertThat(LeaderboardScoring.score(ScoreMode.GROWTH, 8.0, 12.0, 8.0, 1.0)).hasToString("1000.00");
    }

    @Test
    @DisplayName("the GROWTH ceiling is exactly 12,250")
    void growthCeilingIsExact() {
        // Maximal ambition, fully delivered. 100 x (0.1 + 0.9 x 1.25).
        assertThat(LeaderboardScoring.ceiling(ScoreMode.GROWTH, 4.0, 8.0)).hasToString("12250.00");
    }

    @Test
    @DisplayName("the minimum-gap ceiling is exactly 10,562.50")
    void minimumGapCeilingIsExact() {
        // Ambition 0.25, fully delivered: 100 x (0.1 + 0.9 x 1.0625) = 105.625.
        assertThat(LeaderboardScoring.ceiling(ScoreMode.GROWTH, 8.0, 9.0)).hasToString("10562.50");
    }

    @Test
    @DisplayName("a first-year's baseline of zero earns no ambition bonus")
    void firstYearAmbitionIsForcedToZero() {
        // Zero is a real baseline — the transcript parses, the current term is just ungraded — but
        // it is degenerate here: min(4.0, 12.0 - 0) is 4.0, so any ordinary target would clamp to a
        // full bonus. They are scored on attainment toward their target, capped at 10,000.
        assertThat(LeaderboardScoring.ambition(0.0, 8.0)).isCloseTo(0.0, within(EPSILON));
        assertThat(LeaderboardScoring.ceiling(ScoreMode.GROWTH, 0.0, 8.0)).hasToString("10000.00");
        assertThat(LeaderboardScoring.score(ScoreMode.GROWTH, 0.0, 8.0, 4.0, 1.0)).hasToString("5500.00");
    }

    @Test
    @DisplayName("a delivered goal beats a missed one, which is the calibration incentive")
    void deliveredBeatsOverreached() {
        // Both students gained a real 1.0 point. A hit their goal; B missed theirs by three.
        var hit = LeaderboardScoring.score(ScoreMode.GROWTH, 8.0, 9.0, 9.0, 1.0);
        var missed = LeaderboardScoring.score(ScoreMode.GROWTH, 8.0, 12.0, 9.0, 1.0);

        assertThat(hit).hasToString("10562.50");
        assertThat(missed).hasToString("3812.50");
    }

    @Test
    @DisplayName("ambition clamps at both ends")
    void ambitionClamps() {
        assertThat(LeaderboardScoring.ambition(8.0, 7.0)).isCloseTo(0.0, within(EPSILON));
        assertThat(LeaderboardScoring.ambition(8.0, 20.0)).isCloseTo(1.0, within(EPSILON));
    }
}
