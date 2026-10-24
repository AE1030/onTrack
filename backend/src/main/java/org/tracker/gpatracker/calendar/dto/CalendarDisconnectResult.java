package org.tracker.gpatracker.calendar.dto;

/**
 * Outcome of disconnecting an external calendar.
 *
 * @param eventsRemoved    events deleted from the external calendar; zero when removal was not asked for
 * @param eventsNotRemoved events removal was asked for but that are still in the external calendar,
 *                         because the provider refused or the account's access had lapsed
 */
public record CalendarDisconnectResult(int eventsRemoved, int eventsNotRemoved) {
}
