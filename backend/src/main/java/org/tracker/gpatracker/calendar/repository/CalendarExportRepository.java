package org.tracker.gpatracker.calendar.repository;

import org.tracker.gpatracker.calendar.model.CalendarExport;
import org.tracker.gpatracker.calendar.model.CalendarProvider;
import org.tracker.gpatracker.tenancy.UserScopedRepository;

import java.util.Optional;

public interface CalendarExportRepository
        extends UserScopedRepository<CalendarExport, String> {

    /**
     * The newest snapshot for one connected account, which the sync diffs the current projection
     * against. A null {@code accountEmail} is matched with {@code IS NULL} by Spring Data.
     */
    Optional<CalendarExport> findTopByOwnerIdAndProviderAndAccountEmailOrderByExportedAtDesc(
            Long ownerId, CalendarProvider provider, String accountEmail);
}
