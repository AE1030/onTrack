package org.tracker.gpatracker.calendar.event;

/**
 * Published once a student's calendar projection has been rebuilt and saved.
 *
 * <p>A second event rather than a second listener on {@link AssessmentTableUpdatedEvent}: Spring
 * does not order listeners of one event unless told to, and pushing to external calendars must
 * happen after the projection it reads has been written.
 */
public record CalendarProjectionUpdatedEvent(Long studentId) {
}
