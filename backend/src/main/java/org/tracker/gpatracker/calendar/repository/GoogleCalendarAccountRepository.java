package org.tracker.gpatracker.calendar.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tracker.gpatracker.calendar.model.GoogleCalendarAccount;
import org.tracker.gpatracker.calendar.model.CalendarProvider;

import java.util.Optional;

public interface GoogleCalendarAccountRepository extends JpaRepository<GoogleCalendarAccount, Long> {
    Optional<GoogleCalendarAccount> findByUserIdAndProvider(Long userId, CalendarProvider provider);
}
