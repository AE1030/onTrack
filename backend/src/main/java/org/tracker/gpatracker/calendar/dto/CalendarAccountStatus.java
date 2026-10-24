package org.tracker.gpatracker.calendar.dto;

import org.tracker.gpatracker.calendar.model.CalendarProvider;

/**
 * One connected external calendar, as the app sees it. Deliberately leaves out the tokens.
 *
 * @param needsReconnect the provider revoked access; syncs to this account are paused until the
 *                       student reconnects
 */
public record CalendarAccountStatus(CalendarProvider provider, String email, boolean needsReconnect) {
}
