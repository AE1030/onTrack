package org.tracker.gpatracker.calendar.exception;

/**
 * A disconnect arrived while a sync of the same account was running. Removing the account under a
 * running sync would let that sync save a snapshot for an account that no longer exists, so the
 * disconnect is refused and the student tries again a moment later.
 */
public class CalendarSyncInProgressException extends RuntimeException {

    public CalendarSyncInProgressException(String message) {
        super(message);
    }
}
