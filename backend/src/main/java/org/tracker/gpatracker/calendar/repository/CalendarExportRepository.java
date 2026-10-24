package org.tracker.gpatracker.calendar.repository;

import org.tracker.gpatracker.calendar.model.CalendarExport;
import org.tracker.gpatracker.calendar.model.CalendarProvider;
import org.tracker.gpatracker.tenancy.UserScopedRepository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface CalendarExportRepository
        extends UserScopedRepository<CalendarExport, String> {

    /**
     * The newest snapshot for one connected account, which the sync diffs the current projection
     * against. A null {@code accountEmail} is matched with {@code IS NULL} by Spring Data.
     */
    Optional<CalendarExport> findTopByOwnerIdAndProviderAndAccountEmailOrderByExportedAtDesc(
            Long ownerId, CalendarProvider provider, String accountEmail);

    /**
     * Deletes every snapshot for one account except {@code keepId}, the one just saved.
     *
     * <p>Each sync appends a snapshot and only the newest per account is ever read, so without this
     * the table grows by one row per table save per connected account. Snapshots for the student's
     * other account emails are untouched: if they switch back to an old address, its last snapshot
     * is what stops the sync re-creating every event already sitting in that calendar.
     *
     * <p>The owner is in the where clause explicitly, since Hibernate filters do not apply to bulk
     * deletes. A null email is matched as null, the same as the finder above; it gets its own query
     * because Postgres cannot type a null bound to {@code :email is null}.
     */
    @Transactional
    default int deleteOlderSnapshots(Long ownerId, CalendarProvider provider, String accountEmail, String keepId) {
        return accountEmail == null
                ? deleteOlderSnapshotsWithoutEmail(ownerId, provider, keepId)
                : deleteOlderSnapshotsForEmail(ownerId, provider, accountEmail, keepId);
    }

    @Transactional
    @Modifying
    @Query("""
            delete from CalendarExport e
            where e.ownerId = :ownerId and e.provider = :provider
              and e.accountEmail = :accountEmail and e.id <> :keepId
            """)
    int deleteOlderSnapshotsForEmail(@Param("ownerId") Long ownerId,
                                     @Param("provider") CalendarProvider provider,
                                     @Param("accountEmail") String accountEmail,
                                     @Param("keepId") String keepId);

    @Transactional
    @Modifying
    @Query("""
            delete from CalendarExport e
            where e.ownerId = :ownerId and e.provider = :provider
              and e.accountEmail is null and e.id <> :keepId
            """)
    int deleteOlderSnapshotsWithoutEmail(@Param("ownerId") Long ownerId,
                                         @Param("provider") CalendarProvider provider,
                                         @Param("keepId") String keepId);
}
