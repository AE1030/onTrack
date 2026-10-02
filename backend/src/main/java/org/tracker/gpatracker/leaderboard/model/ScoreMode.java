package org.tracker.gpatracker.leaderboard.model;

/**
 * Which scoring shape a student's season runs under.
 *
 * <p>Decided once at onboarding from the baseline and the target, then frozen alongside them. It
 * cannot be switched mid-season to whichever one is paying better in week 11.
 *
 * <p>The distinction between {@link #CEILING} and {@link #MAINTENANCE} is the whole point of having
 * three modes rather than two: being unable to grow is not the same as choosing not to. A student
 * at 11.5 cannot set an ambitious target because the scale is out of room, and penalising them for
 * that would be punishing them for already being excellent. A student at 6.0 who targets 6.0 has
 * the room and declined it.
 */
public enum ScoreMode {

    /** Room to climb, and a goal at least a point above the baseline. Tops out at 12,250. */
    GROWTH,

    /** Baseline above 11.0, so growth is arithmetically unavailable. Holding pays the full 10,000. */
    CEILING,

    /** Room available, but the student chose a flat goal. Holding pays up to 7,000. */
    MAINTENANCE
}
