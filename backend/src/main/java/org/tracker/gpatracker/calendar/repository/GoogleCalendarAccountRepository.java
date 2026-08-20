package org.tracker.gpatracker.calendar.repository;

import org.tracker.gpatracker.calendar.model.GoogleCalendarAccount;
import org.tracker.gpatracker.tenancy.UserScopedRepository;
import org.tracker.gpatracker.calendar.model.CalendarProvider;

import java.util.Optional;

public interface GoogleCalendarAccountRepository extends UserScopedRepository<GoogleCalendarAccount, Long> {
    Optional<GoogleCalendarAccount> findByOwnerIdAndProvider(Long studentId, CalendarProvider provider);
}
