package org.tracker.gpatracker.leaderboard.model;

/**
 * The lifecycle of one student's season entry.
 *
 * <p>Only the first two are reachable in v2. The remaining three arrive with transcript settlement
 * in v3, and are declared now so that adding them is not a migration — the column is a string enum
 * from the start precisely so the schema does not have to change when settlement lands.
 */
public enum EntryStatus {

    /** Joined, and scored from self-reported grades. The only ranked status in v2. */
    ACTIVE,

    /**
     * Opted out mid-season. Dropped from the ranked read and no longer displayed, but never
     * deleted: the frozen baseline and target survive so that rejoining restores the same starting
     * line rather than handing out a fresh one.
     */
    WITHDRAWN,

    /** v3: rescored against the transcript once the season closed. */
    SETTLED,

    /** v3: the season closed with no transcript to settle against. */
    UNVERIFIED,

    /** v3: settlement found the reported grades irreconcilable with the transcript. */
    VOID;

    /** Whether an entry in this status appears on the board. */
    public boolean isRanked() {
        return this == ACTIVE || this == SETTLED;
    }
}
