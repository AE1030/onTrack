package org.tracker.gpatracker.leaderboard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tracker.gpatracker.leaderboard.model.ScoreMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Which game a student is playing, and why there are three of them rather than two.
 *
 * <p>The distinction the modes encode is that being unable to grow is not the same as choosing not
 * to. Collapsing CEILING and MAINTENANCE either punishes the strongest students for having nowhere
 * to climb, or makes coasting pay the same as striving.
 */
class ModeSelectionTest {

    private static final double EPSILON = 1e-9;

    @Test
    @DisplayName("held is 1.0 at or above baseline and decays to zero two points below")
    void heldMatchesTheRecoveryBand() {
        assertThat(LeaderboardScoring.held(8.0, 9.0)).isCloseTo(1.0, within(EPSILON));
        assertThat(LeaderboardScoring.held(8.0, 8.0)).isCloseTo(1.0, within(EPSILON));
        assertThat(LeaderboardScoring.held(8.0, 7.0)).isCloseTo(0.5, within(EPSILON));
        assertThat(LeaderboardScoring.held(8.0, 6.0)).isCloseTo(0.0, within(EPSILON));
        assertThat(LeaderboardScoring.held(8.0, 3.0)).isCloseTo(0.0, within(EPSILON));
    }

    /**
     * The regression an earlier draft shipped: requiring a full point of climb from everyone barred
     * students above 11.0 from joining at all, because the scale stops at 12.
     */
    @Test
    @DisplayName("a baseline above 11.0 routes to CEILING rather than being rejected")
    void strongStudentsAreNeverBarred() {
        assertThat(LeaderboardScoring.modeFor(11.5, 12.0)).isEqualTo(ScoreMode.CEILING);
        assertThat(LeaderboardScoring.modeFor(11.5, 11.5)).isEqualTo(ScoreMode.CEILING);
        assertThat(LeaderboardScoring.modeFor(11.1, 12.0)).isEqualTo(ScoreMode.CEILING);

        // And holding that level pays in full. Excellence is not penalised for having nowhere left.
        assertThat(LeaderboardScoring.ceiling(ScoreMode.CEILING, 11.5, 12.0)).hasToString("10000.00");
    }

    @Test
    @DisplayName("exactly 11.0 still has room, so it is growth")
    void theBoundaryIsInclusiveOfGrowth() {
        assertThat(LeaderboardScoring.modeFor(11.0, 12.0)).isEqualTo(ScoreMode.GROWTH);
    }

    @Test
    @DisplayName("a chosen flat target routes to MAINTENANCE, not to a rejection")
    void aFlatTargetIsALegitimateChoice() {
        assertThat(LeaderboardScoring.modeFor(6.0, 6.0)).isEqualTo(ScoreMode.MAINTENANCE);
        assertThat(LeaderboardScoring.modeFor(6.0, 6.9)).isEqualTo(ScoreMode.MAINTENANCE);
        assertThat(LeaderboardScoring.ceiling(ScoreMode.MAINTENANCE, 6.0, 6.0)).hasToString("7000.00");
    }

    @Test
    @DisplayName("coasting loses to a partially delivered ambitious goal")
    void strivingOutranksCoasting() {
        // The ordering the whole feature rests on. A student at 4.0 who targets 8.0 and reaches 7.0
        // must beat a student who declared nothing and held their level -- otherwise the rational
        // move is to coast, which is precisely the behaviour this exists to discourage.
        var striving = LeaderboardScoring.score(ScoreMode.GROWTH, 4.0, 8.0, 7.0, 1.0);
        var coasting = LeaderboardScoring.score(ScoreMode.MAINTENANCE, 4.0, 4.0, 4.0, 1.0);

        assertThat(striving).hasToString("9437.50");
        assertThat(coasting).hasToString("7000.00");
        assertThat(striving).isGreaterThan(coasting);
    }

    @Test
    @DisplayName("the three ceilings are 12,250, 10,000 and 7,000")
    void ceilingsAreDistinct() {
        assertThat(LeaderboardScoring.ceiling(ScoreMode.GROWTH, 4.0, 8.0)).hasToString("12250.00");
        assertThat(LeaderboardScoring.ceiling(ScoreMode.CEILING, 11.5, 11.5)).hasToString("10000.00");
        assertThat(LeaderboardScoring.ceiling(ScoreMode.MAINTENANCE, 4.0, 4.0)).hasToString("7000.00");
    }

    @Test
    @DisplayName("neither holding mode has a dead zone the growth curve avoids")
    void holdingModesDecayLikeTheRecoveryBand() {
        // Both branches use the same 2.0 span, so a student one point down under CEILING is half
        // way through their band, exactly as a GROWTH student one point down is.
        assertThat(LeaderboardScoring.score(ScoreMode.CEILING, 11.5, 12.0, 10.5, 1.0)).hasToString("5000.00");
        assertThat(LeaderboardScoring.score(ScoreMode.MAINTENANCE, 6.0, 6.0, 5.0, 1.0)).hasToString("3500.00");
    }
}
