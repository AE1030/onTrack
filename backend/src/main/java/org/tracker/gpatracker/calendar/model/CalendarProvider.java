package org.tracker.gpatracker.calendar.model;

/**
 * Stored by name in {@code calendar_account.provider} and {@code calendar_export.provider}, both of
 * which carry a CHECK constraint listing the allowed values. Adding a constant here needs a Flyway
 * migration widening those constraints, or the first insert of the new value fails.
 */
public enum CalendarProvider {
    GOOGLE,
    MICROSOFT
}
