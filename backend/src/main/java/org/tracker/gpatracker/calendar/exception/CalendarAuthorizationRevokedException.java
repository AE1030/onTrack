package org.tracker.gpatracker.calendar.exception;

/**
 * The provider rejected the refresh token itself (Google's {@code invalid_grant}): the student
 * revoked access, or the token expired. The account is no use until they reconnect.
 *
 * <p>Thrown only for that case. A provider outage or network failure during a refresh must not
 * throw this, because the sync deletes the account when it sees it.
 */
public class CalendarAuthorizationRevokedException extends RuntimeException {

    public CalendarAuthorizationRevokedException(String message, Throwable cause) {
        super(message, cause);
    }
}
