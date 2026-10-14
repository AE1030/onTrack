package org.tracker.gpatracker.calendar.repository;

import org.tracker.gpatracker.calendar.model.CalendarAccount;
import org.tracker.gpatracker.tenancy.UserScopedRepository;
import org.tracker.gpatracker.calendar.model.CalendarProvider;

import java.util.List;
import java.util.Optional;

public interface CalendarAccountRepository extends UserScopedRepository<CalendarAccount, Long> {
    Optional<CalendarAccount> findByOwnerIdAndProvider(Long studentId, CalendarProvider provider);

    /** Every provider the student has connected; at most one row each, per the unique index. */
    List<CalendarAccount> findByOwnerId(Long studentId);
}
