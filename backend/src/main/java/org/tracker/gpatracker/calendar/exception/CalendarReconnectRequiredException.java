package org.tracker.gpatracker.calendar.exception;

/**
 * The sync cannot run until the student goes through OAuth again: the account was never
 * connected, or its authorization was revoked and the account has been removed.
 *
 * <p>The only exception the sync endpoint turns into its "connect" response. It used to catch every
 * {@link IllegalStateException}, which sent students through OAuth for problems a reconnect could
 * not fix and left them in a loop.
 */
public class CalendarReconnectRequiredException extends RuntimeException {

    public CalendarReconnectRequiredException(String message) {
        super(message);
    }
}
