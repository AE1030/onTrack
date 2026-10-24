package org.tracker.gpatracker.calendar.exception;

/**
 * A sync could not run for a reason that should pass on its own, such as the provider being down
 * during a token refresh. The account is kept, and the next sync tries again.
 *
 * <p>Deliberately not an {@link IllegalStateException}: the sync endpoint turns those into a
 * reconnect prompt, and reconnecting would not help here.
 */
public class CalendarSyncUnavailableException extends RuntimeException {

    public CalendarSyncUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
