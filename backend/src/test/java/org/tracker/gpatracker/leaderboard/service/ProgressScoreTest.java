package org.tracker.gpatracker.leaderboard.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.tracker.gpatracker.leaderboard.model.ScoreMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The progress curve: what a projected GPA is worth against a baseline and a target.
 *
 * <p>The two properties that matter most are that it saturates — overshooting pays exactly what
 * reaching pays, so there is no unbounded payoff to inflating grades — and that it is continuous at
 * the baseline, so a student crossing their own starting line sees no jump or dip.
 */
class ProgressScoreTest {

    private static final double BASELINE = 8.0;
    private static final double TARGET = 10.0;
    private static final double EPSILON = 1e-9;

    @Test
    @DisplayName("reaching the target is exactly 1.0, and overshooting clamps there")
    void reachingTheTargetSaturates() {
        assertThat(LeaderboardScoring.progress(BASELINE, TARGET, 10.0)).isCloseTo(1.0, within(EPSILON));
        assertThat(LeaderboardScoring.progress(BASELINE, TARGET, 12.0)).isCloseTo(1.0, within(EPSILON));
    }

    @Test
    @DisplayName("sitting exactly on the baseline is exactly the recovery band")
    void baselineIsTheTopOfTheRecoveryBand() {
        assertThat(LeaderboardScoring.progress(BASELINE, TARGET, BASELINE))
                .isCloseTo(0.1, within(EPSILON));
    }

    @Test
    @DisplayName("the two branches meet without a step at the baseline")
    void branchesMeetAtTheBaseline() {
        double justBelow = LeaderboardScoring.progress(BASELINE, TARGET, BASELINE - 1e-6);
        double justAbove = LeaderboardScoring.progress(BASELINE, TARGET, BASELINE + 1e-6);

        assertThat(justBelow).isCloseTo(0.1, within(1e-6));
        assertThat(justAbove).isCloseTo(0.1, within(1e-6));
    }

    @Test
    @DisplayName("recovery decays to zero two points down, and stays there below")
    void recoveryBottomsOutTwoPointsDown() {
        assertThat(LeaderboardScoring.progress(BASELINE, TARGET, 6.0)).isCloseTo(0.0, within(EPSILON));
        assertThat(LeaderboardScoring.progress(BASELINE, TARGET, 4.0)).isCloseTo(0.0, within(EPSILON));
        assertThat(LeaderboardScoring.progress(BASELINE, TARGET, 0.0)).isCloseTo(0.0, within(EPSILON));
    }

    /**
     * The worked example from the plan. Baseline 8.0, target 10.0, ambition 0.5 — the whole curve
     * in six points, including the fact that the 6.5 to 7.5 climb triples the score while still
     * leaving them far below anyone actually making progress.
     */
    @ParameterizedTest(name = "projected {0} scores {1}")
    @CsvSource({
            "6.0,      0.00",
            "6.5,    250.00",
            "7.5,    750.00",
            "8.0,   1000.00",
            "9.0,   6062.50",
            "10.0, 11125.00"
    })
    @DisplayName("the published worked example holds end to end")
    void workedExample(double projected, String expected) {
        assertThat(LeaderboardScoring.score(ScoreMode.GROWTH, BASELINE, TARGET, projected, 1.0))
                .hasToString(expected);
    }

    @Test
    @DisplayName("a lower branch scaled by the gap would be wrong, so it is not")
    void recoveryWindowDoesNotStretchWithAmbition() {
        // Two students, each 1.5 points down, aiming very differently. Being behind means the same
        // thing for both: the recovery span is fixed at 2.0 and has nothing to do with the target.
        double modest = LeaderboardScoring.progress(8.0, 9.0, 6.5);
        double ambitious = LeaderboardScoring.progress(8.0, 12.0, 6.5);

        assertThat(modest).isCloseTo(ambitious, within(EPSILON));
    }

    @Test
    @DisplayName("GROWTH never divides by a gap that can approach zero")
    void growthNeverDividesByANearZeroGap() {
        // modeFor guarantees the gap before progress() ever runs, which is what makes the old
        // max(target - baseline, 0.1) epsilon guard dead code rather than a defence still needed.
        assertThat(LeaderboardScoring.modeFor(8.0, 8.0)).isNotEqualTo(ScoreMode.GROWTH);
        assertThat(LeaderboardScoring.modeFor(8.0, 8.5)).isNotEqualTo(ScoreMode.GROWTH);
        assertThat(LeaderboardScoring.modeFor(8.0, 9.0)).isEqualTo(ScoreMode.GROWTH);

        // And it stays total even so: called with a gap it should never see, it does not divide.
        assertThat(LeaderboardScoring.progress(8.0, 8.0, 9.0)).isCloseTo(0.1, within(EPSILON));
    }

    @Test
    @DisplayName("behaviour damping scales the whole score, floor included")
    void behaviorScoreScalesTheResult() {
        assertThat(LeaderboardScoring.score(ScoreMode.GROWTH, BASELINE, TARGET, 10.0, 0.5))
                .hasToString("5562.50");
    }
}
