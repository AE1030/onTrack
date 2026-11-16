package org.tracker.gpatracker.calendar.exception;

/**
 * The student finished the provider's consent screen but did not grant calendar access.
 *
 * <p>Google shows each requested scope as its own checkbox, and the calendar one can be left
 * unticked. The code exchange still succeeds, so without this check the account was saved as
 * connected and every write after it failed with 403 insufficient scopes, out of sight of the
 * student.
 */
public class CalendarScopeNotGrantedException extends RuntimeException {

    public CalendarScopeNotGrantedException(String message) {
        super(message);
    }
}
