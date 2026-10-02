package org.tracker.gpatracker.calendar.repository;

import org.tracker.gpatracker.calendar.model.CalendarEvents;
import org.tracker.gpatracker.tenancy.UserScopedRepository;

import java.util.Optional;

public interface CalendarEventsRepository extends UserScopedRepository<CalendarEvents, String> {

    /** At most one projection row per student; the unique constraint on student_id is what guarantees it. */
    Optional<CalendarEvents> findByOwnerId(Long ownerId);
}
