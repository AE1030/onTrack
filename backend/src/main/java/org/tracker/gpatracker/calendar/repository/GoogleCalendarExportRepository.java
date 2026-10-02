package org.tracker.gpatracker.calendar.repository;

import org.tracker.gpatracker.calendar.model.GoogleCalendarExport;
import org.tracker.gpatracker.tenancy.UserScopedRepository;

import java.util.Optional;

public interface GoogleCalendarExportRepository
        extends UserScopedRepository<GoogleCalendarExport, String> {

    /** The newest snapshot, which the sync diffs the current projection against. */
    Optional<GoogleCalendarExport> findTopByOwnerIdOrderByExportedAtDesc(Long ownerId);
}
