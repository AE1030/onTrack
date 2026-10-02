package org.tracker.gpatracker.leaderboard.service;

import org.tracker.gpatracker.leaderboard.model.ScoreMode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The whole scoring formula, as pure arithmetic.
 *
 * <p>No repositories, no clock, no context — everything here is a function of a baseline, a target,
 * a projected GPA and a behaviour multiplier. That is deliberate: the arithmetic is the part of
 * this feature most worth testing exhaustively and least worth mocking a database for.
 *
 * <p>The shape, in one place:
 *
 * <pre>
 *   ambition  = clamp((target − baseline) / min(4.0, 12.0 − baseline), 0, 1)
 *   progress  = at or above baseline → 0.1 + 0.9 × clamp((projected − baseline) / (target − baseline), 0, 1)
 *               below baseline       → 0.1 × clamp((projected − baseline + 2.0) / 2.0, 0, 1)
 *   recovered = min(progress, 0.1)
 *   earned    = max(0, progress − 0.1)
 *   score     = 10,000 × (recovered + earned × (1 + 0.25 × ambition)) × behaviourScore
 * </pre>
 *
 * <p>Three properties are worth stating because the rest of the design leans on them:
 *
 * <ul>
 *   <li><b>It saturates.</b> Progress is capped at 1.0, so faking straight 100s lands in exactly
 *       the same place as honestly hitting the target. There is no unbounded payoff to lying,
 *       which is a stronger guarantee than any detector v2 could have shipped.</li>
 *   <li><b>The bonus multiplies rather than adds.</b> Declaring a target is free; if ambition stood
 *       alone every student would type 12.0 on day one and the field would be flat. Because it
 *       multiplies {@code earned}, a target nobody approaches pays nothing.</li>
 *   <li><b>The first tenth is not earned.</b> Splitting progress into {@code recovered} and
 *       {@code earned} is what stops the ambition bonus paying out on the recovery band — two
 *       students sitting at their baselines score exactly 1,000 whatever they typed at onboarding.
 *       The cost is a ceiling of 12,250 rather than 12,500.</li>
 * </ul>
 *
 * <p><b>Why 10,000 and not 100.</b> The formula is unchanged from the original 0-to-100 version;
 * every score is simply multiplied by 100. Larger point values read as more reward for the same
 * progress, and a jump from 1,000 to 6,063 lands harder than one from 10 to 60.6. Rankings are
 * identical under the multiplier, so nothing about fairness moved.
 */
public final class LeaderboardScoring {

    /** The top of the McMaster 12-point scale. */
    public static final double SCALE_MAX = 12.0;

    /** The widest gap ambition is measured against, for a student with room to spare. */
    public static final double MAX_REACH = 4.0;

    /** GROWTH requires at least this much declared climb; below it the student is maintaining. */
    public static final double MIN_GROWTH_GAP = 1.0;

    /** How far below baseline the recovery band reaches before the score is simply zero. */
    public static final double RECOVERY_SPAN = 2.0;

    /** The share of the progress scale reserved for recovery — earned only by being at baseline. */
    public static final double RECOVERY_BAND = 0.1;

    /** How much a fully delivered maximal goal adds on top of the earned portion. */
    public static final double AMBITION_BONUS = 0.25;

    /** A fully delivered target with no ambition bonus, and the CEILING mode's best score. */
    public static final double FULL_SCORE = 10_000.0;

    /** MAINTENANCE holds pay less than striving does. Not a penalty; a lower ceiling. */
    public static final double MAINTENANCE_CEILING = 0.7 * FULL_SCORE;

    private LeaderboardScoring() {
    }

    // ------------------------------------------------------------------ mode selection

    /**
     * Which mode a baseline and target select. Decided once at onboarding and then frozen.
     *
     * <p>This is also what closes the exploit the original formula had: dividing by
     * {@code max(target − baseline, 0.1)} meant a student who set target equal to baseline got a
     * denominator of 0.1, so their first grade clamped straight to a perfect score. Routing that
     * student to MAINTENANCE instead of dividing by a gap they do not have closes it at the root,
     * and makes the epsilon guard unreachable rather than merely unlikely.
     */
    public static ScoreMode modeFor(double baseline, double target) {
        if (headroom(baseline) < MIN_GROWTH_GAP) {
            // Above 11.0 there is no room to be ambitious, so holding is the whole game. Blocking
            // these students instead — which an earlier draft's "gap ≥ 1.0 for everyone" rule did
            // — would have barred the strongest students from a leaderboard about standing.
            return ScoreMode.CEILING;
        }
        return target - baseline >= MIN_GROWTH_GAP ? ScoreMode.GROWTH : ScoreMode.MAINTENANCE;
    }

    /** How much of the scale is left above this student. */
    public static double headroom(double baseline) {
        return SCALE_MAX - baseline;
    }

    // ----------------------------------------------------------------------- ambition

    /**
     * How much of their available reach the student claimed, from 0 to 1.
     *
     * <p>Normalised by headroom rather than by a fixed span, which is the detail that makes it
     * fair: a student at 11.0 targeting 12.0 has reached as high as the scale permits and scores
     * 1.0, exactly like a student at 4.0 targeting 8.0. Being already good never caps how ambitious
     * you are allowed to look.
     *
     * <p>A baseline of exactly zero is forced to 0. A first-year's transcript parses to a real
     * baseline of 0 — their current term is in progress and nothing is graded yet — but zero is
     * degenerate here: {@code min(4.0, 12.0 − 0)} is 4.0, so any target at or above 4.0 would clamp
     * to a full 1.0 and hand them the maximum bonus for an ordinary goal.
     */
    public static double ambition(double baseline, double target) {
        if (baseline <= 0.0) {
            return 0.0;
        }
        double reach = Math.min(MAX_REACH, headroom(baseline));
        if (reach <= 0.0) {
            return 0.0;
        }
        return clamp((target - baseline) / reach, 0.0, 1.0);
    }

    // ----------------------------------------------------------------------- progress

    /**
     * Fraction of the journey from baseline to target that has been covered, from 0 to 1.
     *
     * <p>Two branches, because they measure different journeys. Above the baseline you are
     * travelling from your baseline to your target, so the distance is {@code target − baseline}.
     * Below it you are travelling from wherever you fell back up to your own starting line — the
     * target is not involved, because you are not moving toward it yet.
     *
     * <p>That is also why the lower branch divides by a fixed 2.0 rather than by the gap. Dividing
     * by the gap would stretch the recovery window with your ambition, so a 4-point goal got a
     * 4-point window and a 1-point goal got a 1-point one. How far you fell has nothing to do with
     * how high you aimed.
     *
     * <p>Only defined for GROWTH. The other two modes measure {@link #held} instead.
     */
    public static double progress(double baseline, double target, double projected) {
        if (projected < baseline) {
            return RECOVERY_BAND * clamp((projected - baseline + RECOVERY_SPAN) / RECOVERY_SPAN, 0.0, 1.0);
        }
        double gap = target - baseline;
        if (gap <= 0.0) {
            // Unreachable through modeFor, which routes a non-existent gap to MAINTENANCE. Kept as
            // a total function rather than a guard: it means callers of progress() cannot divide by
            // zero even if a future mode change forgets that invariant.
            return RECOVERY_BAND;
        }
        return RECOVERY_BAND + (1.0 - RECOVERY_BAND) * clamp((projected - baseline) / gap, 0.0, 1.0);
    }

    /**
     * How well the student is holding their level, from 0 to 1.
     *
     * <p>1.0 at or above baseline, decaying to zero two points below it — the same span as the
     * recovery band, so neither CEILING nor MAINTENANCE has a dead zone the GROWTH curve avoids.
     * Holding your level is a full result in the modes that are about holding.
     */
    public static double held(double baseline, double projected) {
        return clamp((projected - baseline + RECOVERY_SPAN) / RECOVERY_SPAN, 0.0, 1.0);
    }

    // -------------------------------------------------------------------------- score

    /**
     * The published score, 0 to 12,250, rounded to the two decimals the column stores.
     *
     * @param behaviorScore the scheduled damping multiplier, 0.5 to 1.0 — see {@code BehaviorScoring}
     */
    public static BigDecimal score(ScoreMode mode,
                                   double baseline,
                                   double target,
                                   double projected,
                                   double behaviorScore) {
        return round(rawScore(mode, baseline, target, projected) * behaviorScore);
    }

    /** The score before behaviour damping. Split out so the ceilings can be stated without one. */
    public static double rawScore(ScoreMode mode, double baseline, double target, double projected) {
        return switch (mode) {
            case GROWTH -> growthScore(baseline, target, projected);
            case CEILING -> FULL_SCORE * held(baseline, projected);
            case MAINTENANCE -> MAINTENANCE_CEILING * held(baseline, projected);
        };
    }

    private static double growthScore(double baseline, double target, double projected) {
        double progress = progress(baseline, target, projected);

        // recovered + earned is always exactly progress. The split creates no new quantity; it
        // exists only so the ambition bonus can land on the second half alone. Anyone at or below
        // baseline has earned = 0, so a declared target pays them nothing until they start climbing.
        double recovered = Math.min(progress, RECOVERY_BAND);
        double earned = Math.max(0.0, progress - RECOVERY_BAND);

        double bonus = 1.0 + AMBITION_BONUS * ambition(baseline, target);
        return FULL_SCORE * (recovered + earned * bonus);
    }

    /**
     * The best score this baseline and target can produce, before behaviour damping.
     *
     * <p>Shown live at onboarding as the student moves their target: the reward for reaching is
     * only motivating if it is visible at the moment of choosing.
     */
    public static BigDecimal ceiling(ScoreMode mode, double baseline, double target) {
        // A projected GPA at or above the target saturates every branch, so the target itself is
        // the best case for GROWTH and the baseline is the best case for the holding modes.
        double best = mode == ScoreMode.GROWTH ? target : baseline;
        return round(rawScore(mode, baseline, target, best));
    }

    // ------------------------------------------------------------------------ helpers

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Rounded twice, on purpose.
     *
     * <p>0.1 has no exact binary representation, so a score the formula puts at exactly 9437.5 can
     * arrive here as 9437.499999999999 and round down. The first pass absorbs that accumulated
     * error; the second produces the two decimals the column stores. Without it, HALF_UP tips the
     * wrong way on precisely the exact-half cases the published examples are made of.
     */
    static BigDecimal round(double value) {
        return BigDecimal.valueOf(value)
                .setScale(6, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
