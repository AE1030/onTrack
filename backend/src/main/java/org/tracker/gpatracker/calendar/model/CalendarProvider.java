package org.tracker.gpatracker.calendar.model;

/**
 * Stored by name in {@code calendar_account.provider}, which carries a CHECK constraint listing the
 * allowed values. Adding a constant here needs a Flyway migration widening that constraint, or the
 * first insert of the new value fails.
 */
public enum CalendarProvider {
    GOOGLE,
    MICROSOFT
}
