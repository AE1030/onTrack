package org.tracker.gpatracker.leaderboard.dto;

/**
 * Whether a handle could be claimed right now, and if not, why.
 *
 * <p>Answers rather than throws, because this is what a student sees while they are still typing.
 * A rejection here is information, not a failure: the same checks run again when they submit, and
 * that is where a refusal becomes an error status.
 *
 * @param available true when nothing stands in the way of claiming this handle
 * @param reason    null when available, otherwise a message written to be shown as-is
 */
public record HandleCheckResponse(boolean available, String reason) {

    public static HandleCheckResponse free() {
        return new HandleCheckResponse(true, null);
    }

    public static HandleCheckResponse taken(String reason) {
        return new HandleCheckResponse(false, reason);
    }
}
