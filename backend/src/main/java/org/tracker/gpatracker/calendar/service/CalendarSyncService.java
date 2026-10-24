package org.tracker.gpatracker.calendar.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.tracker.gpatracker.calendar.dto.CalendarAccountStatus;
import org.tracker.gpatracker.calendar.dto.CalendarDisconnectResult;
import org.tracker.gpatracker.calendar.dto.OAuthTokens;
import org.tracker.gpatracker.calendar.exception.CalendarAuthorizationRevokedException;
import org.tracker.gpatracker.calendar.exception.CalendarReconnectRequiredException;
import org.tracker.gpatracker.calendar.exception.CalendarSyncInProgressException;
import org.tracker.gpatracker.calendar.exception.CalendarSyncUnavailableException;
import org.tracker.gpatracker.calendar.model.CalendarAccount;
import org.tracker.gpatracker.calendar.model.CalendarEvents;
import org.tracker.gpatracker.calendar.model.CalendarEvents.InternalCalendarEvent;
import org.tracker.gpatracker.calendar.model.CalendarExport;
import org.tracker.gpatracker.calendar.model.CalendarExport.ExportedEvent;
import org.tracker.gpatracker.calendar.model.CalendarProvider;
import org.tracker.gpatracker.calendar.repository.CalendarAccountRepository;
import org.tracker.gpatracker.calendar.repository.CalendarExportRepository;
import org.tracker.gpatracker.calendar.strategy.CalendarStrategy;
import org.tracker.gpatracker.calendar.strategy.CalendarStrategyRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Pushes a student's calendar projection to each external calendar they have connected.
 *
 * <p>Provider-neutral: everything here is shared, and every provider-specific call goes through
 * the {@link CalendarStrategy} for the account's provider. Each account is reconciled on its own
 * against its own newest {@link CalendarExport}, so Google and Microsoft never see each other's
 * event ids.
 */
@Service
public class CalendarSyncService {

    private static final Logger logger = LoggerFactory.getLogger(CalendarSyncService.class);

    /**
     * Refresh an access token this long before it expires. Checking against "now" alone let a
     * token with seconds left pass, expire partway through the sync, and fail every call after.
     */
    static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private enum SyncState { RUNNING, RERUN_REQUESTED }

    /**
     * Keyed by student and provider. In-memory, so it only coordinates syncs within one instance;
     * two instances can still sync the same account at once.
     */
    private final ConcurrentHashMap<String, SyncState> inFlight = new ConcurrentHashMap<>();

    private final CalendarProjectionService calendarProjectionService;
    private final CalendarExportRepository exportRepository;
    private final CalendarAccountRepository calendarAccountRepository;
    private final CalendarStrategyRegistry strategyRegistry;

    public CalendarSyncService(
            CalendarProjectionService calendarProjectionService,
            CalendarExportRepository exportRepository,
            CalendarAccountRepository calendarAccountRepository,
            CalendarStrategyRegistry strategyRegistry
    ) {
        this.calendarProjectionService = calendarProjectionService;
        this.exportRepository = exportRepository;
        this.calendarAccountRepository = calendarAccountRepository;
        this.strategyRegistry = strategyRegistry;
    }

    /**
     * Sync every account the student has connected. One account failing (a revoked token, a
     * provider outage) is logged and does not stop the others.
     */
    public void syncAll(Long studentId) {
        for (CalendarAccount account : calendarAccountRepository.findByOwnerId(studentId)) {
            if (account.isNeedsReconnect()) {
                // Already flagged and shown to the student; calling the provider again cannot help.
                continue;
            }
            try {
                sync(studentId, account.getProvider());
            } catch (Exception e) {
                logger.warn("{} calendar sync skipped for student {}: {}",
                        account.getProvider(), studentId, e.getMessage());
            }
        }
    }

    /**
     * Sync one provider.
     *
     * @throws CalendarReconnectRequiredException if the account is not connected or its authorization
     *         has lapsed; the controller turns this into a reconnect prompt
     */
    public void sync(Long studentId, CalendarProvider provider) {
        // At most one sync per account at a time, but a request arriving mid-sync is never
        // dropped: it flags the running sync to go round again, and that next pass reads the
        // projection as it stands by then. Skipping instead would leave the calendar showing
        // whatever the student had before their latest save, until they happened to save again.
        String key = studentId + ":" + provider;
        SyncState state = inFlight.compute(key, (k, current) -> current == null ? SyncState.RUNNING : SyncState.RERUN_REQUESTED);
        if (state == SyncState.RERUN_REQUESTED) {
            logger.info("{} sync already in progress for student {}, queued a rerun", provider, studentId);
            return;
        }
        try {
            do {
                inFlight.put(key, SyncState.RUNNING);
                doSync(studentId, provider);
                // remove(key, RUNNING) only succeeds if nobody asked for a rerun in the meantime.
            } while (!inFlight.remove(key, SyncState.RUNNING));
        } catch (Throwable t) {
            // Not a finally: on the success path the key is already gone, and removing it again
            // could evict a sync another thread started in the gap.
            inFlight.remove(key);
            throw t;
        }
    }

    private void doSync(Long studentId, CalendarProvider provider) {
        CalendarAccount account = calendarAccountRepository
                .findByOwnerIdAndProvider(studentId, provider)
                .orElseThrow(() -> new CalendarReconnectRequiredException(provider + " calendar not connected for student " + studentId));
        if (account.isNeedsReconnect()) {
            throw new CalendarReconnectRequiredException(provider + " calendar needs reconnecting for student " + studentId);
        }
        CalendarStrategy strategy = strategyRegistry.forProvider(provider);

        // Step 1: Get current projection
        // Builds the projection if the student has never saved a table or opened the calendar.
        // An empty projection is a normal sync, not an error: against an existing snapshot it
        // removes everything, which is right for a student who deleted all their assessments.
        CalendarEvents calendarEvents = calendarProjectionService.getCalendarEvents(studentId)
                .orElseGet(CalendarEvents::new);

        List<InternalCalendarEvent> currentEvents = calendarEvents.getEvents() != null
                ? calendarEvents.getEvents()
                : Collections.emptyList();

        // Step 2: Get latest export snapshot for this account
        CalendarExport lastExport = exportRepository
                .findTopByOwnerIdAndProviderAndAccountEmailOrderByExportedAtDesc(studentId, provider, account.getEmail())
                .orElse(null);

        List<ExportedEvent> previousEvents = lastExport != null && lastExport.getEvents() != null
                ? lastExport.getEvents()
                : Collections.emptyList();

        // Step 3: Diff by eventKey
        Map<String, InternalCalendarEvent> currentMap = new LinkedHashMap<>();
        for (InternalCalendarEvent e : currentEvents) {
            currentMap.put(e.getEventKey(), e);
        }

        Map<String, ExportedEvent> previousMap = new LinkedHashMap<>();
        for (ExportedEvent e : previousEvents) {
            previousMap.put(e.getEventKey(), e);
        }

        Set<String> toDelete = previousMap.keySet().stream()
                .filter(key -> !currentMap.containsKey(key))
                .collect(Collectors.toSet());

        Set<String> toAdd = currentMap.keySet().stream()
                .filter(key -> !previousMap.containsKey(key))
                .collect(Collectors.toSet());

        // Step 4: Get credentials
        String accessToken = resolveAccessToken(account, strategy);

        // Step 5: Apply provider operations
        List<ExportedEvent> newExportEvents = new ArrayList<>();

        // Remove events gone from the projection; failed deletes stay in the snapshot for a retry
        int deleted = deleteRemovedEvents(strategy, accessToken, toDelete, previousMap, newExportEvents);

        // Keep existing events that are still current, pushing any whose content changed
        int updated = updateChangedEvents(strategy, accessToken, previousEvents, toDelete, currentMap, newExportEvents);

        // Add new events
        createNewEvents(strategy, accessToken, toAdd, currentMap, newExportEvents);

        // Step 6: Save new export snapshot
        saveSnapshot(studentId, provider, account.getEmail(), newExportEvents);

        logger.info("{} calendar sync completed for student {}: {} added, {} updated, {} deleted",
                provider, studentId, toAdd.size(), updated, deleted);
    }

    /**
     * Carries every surviving snapshot entry into the new snapshot. The eventKey only covers course,
     * name and due date, so an entry whose key still matches can have a new time, description or
     * location; those are detected by content hash and pushed with an update.
     *
     * <p>An entry from before hashes were stored has no hash, so it counts as changed and is
     * updated once, which backfills its hash. A failed update keeps the old entry and its old hash,
     * so the next sync tries again.
     *
     * @return how many events were updated
     */
    private int updateChangedEvents(CalendarStrategy strategy, String accessToken,
                                    List<ExportedEvent> previousEvents, Set<String> toDelete,
                                    Map<String, InternalCalendarEvent> currentMap,
                                    List<ExportedEvent> newExportEvents) {
        int updated = 0;
        for (ExportedEvent prev : previousEvents) {
            String key = prev.getEventKey();
            if (toDelete.contains(key)) continue;

            InternalCalendarEvent current = currentMap.get(key);
            String hash = contentHash(current);
            if (hash.equals(prev.getContentHash()) || prev.getExternalEventId() == null) {
                newExportEvents.add(prev);
                continue;
            }
            try {
                strategy.updateEvent(accessToken, prev.getExternalEventId(), current);
                newExportEvents.add(toExported(current, prev.getExternalEventId()));
                updated++;
                logger.info("Updated {} calendar event: {}", strategy.provider(), key);
            } catch (Exception e) {
                newExportEvents.add(prev);
                logger.error("Failed to update {} calendar event {}: {}", strategy.provider(), key, e.getMessage());
            }
        }
        return updated;
    }

    /**
     * Hash of every field a provider is sent. Two events with the same hash look the same in the
     * external calendar, so no update is needed.
     */
    static String contentHash(InternalCalendarEvent event) {
        String content = String.join("\u0000",
                String.valueOf(event.getCourseCode()),
                String.valueOf(event.getAssessmentName()),
                String.valueOf(event.getDueDate()),
                String.valueOf(event.getStartTime()),
                String.valueOf(event.getEndTime()),
                String.valueOf(event.isAllDay()),
                String.valueOf(event.getDescription()),
                String.valueOf(event.getLocation()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static ExportedEvent toExported(InternalCalendarEvent current, String externalEventId) {
        ExportedEvent exported = new ExportedEvent();
        exported.setCourseCode(current.getCourseCode());
        exported.setAssessmentName(current.getAssessmentName());
        exported.setDueDate(current.getDueDate());
        exported.setStartTime(current.getStartTime());
        exported.setEndTime(current.getEndTime());
        exported.setAllDay(current.isAllDay());
        exported.setEventKey(current.getEventKey());
        exported.setExternalEventId(externalEventId);
        exported.setContentHash(contentHash(current));
        return exported;
    }

    /**
     * Deletes events that are in the snapshot but no longer in the projection.
     *
     * <p>A failed delete keeps its entry in the new snapshot. The snapshot is the only place the
     * event's external id survives once it has left the projection, so dropping the entry would
     * leave the event in the external calendar with nothing left that could ever remove it. Kept,
     * it shows up as a delete again on the next sync.
     *
     * @return how many events were deleted
     */
    private int deleteRemovedEvents(CalendarStrategy strategy, String accessToken,
                                    Set<String> toDelete, Map<String, ExportedEvent> previousMap,
                                    List<ExportedEvent> newExportEvents) {
        int deleted = 0;
        for (String key : toDelete) {
            ExportedEvent prev = previousMap.get(key);
            if (prev.getExternalEventId() == null) continue;
            try {
                strategy.deleteEvent(accessToken, prev.getExternalEventId());
                deleted++;
                logger.info("Deleted {} calendar event: {}", strategy.provider(), key);
            } catch (Exception e) {
                newExportEvents.add(prev);
                logger.error("Failed to delete {} calendar event {}, will retry: {}",
                        strategy.provider(), key, e.getMessage());
            }
        }
        return deleted;
    }

    private void createNewEvents(CalendarStrategy strategy, String accessToken, Set<String> toAdd,
                                 Map<String, InternalCalendarEvent> currentMap,
                                 List<ExportedEvent> newExportEvents) {
        for (String key : toAdd) {
            InternalCalendarEvent current = currentMap.get(key);
            try {
                String externalEventId = strategy.createEvent(accessToken, current);
                newExportEvents.add(toExported(current, externalEventId));
                logger.info("Created {} calendar event: {}", strategy.provider(), key);
            } catch (Exception e) {
                logger.error("Failed to create {} calendar event {}: {}", strategy.provider(), key, e.getMessage());
            }
        }
    }

    /**
     * Runs on the OAuth callback, which is pre-JWT — identity comes from the signed {@code state}
     * token. The caller establishes the tenant scope, so the owner is stamped on persist.
     */
    public void upsertCalendarAccount(Long studentId, CalendarProvider provider, OAuthTokens tokens) {
        calendarAccountRepository
                .findByOwnerIdAndProvider(studentId, provider)
                .ifPresent(calendarAccountRepository::delete);

        CalendarAccount account = new CalendarAccount();
        account.setProvider(provider);
        account.setAccessToken(tokens.accessToken());
        account.setRefreshToken(tokens.refreshToken());
        account.setExpiresAt(tokens.expiresAt());
        account.setEmail(tokens.email());
        calendarAccountRepository.save(account);
    }

    /**
     * Removes the student's account for one provider, optionally deleting the events onTrack pushed
     * to it first.
     *
     * <p>Without removal the account's last snapshot is kept: if the student reconnects the same
     * address later, the snapshot is what stops the sync creating every event a second time. With
     * removal a new snapshot is saved holding only the events that could not be deleted, which is
     * exactly what is still in that calendar, so a later reconnect neither duplicates nor loses them.
     *
     * @return empty if the student has no account for the provider
     * @throws CalendarSyncInProgressException if a sync of this account is running
     */
    public Optional<CalendarDisconnectResult> disconnect(Long studentId, CalendarProvider provider, boolean removeEvents) {
        // Same key as sync(). Holding it keeps a sync from starting mid-disconnect; one that
        // arrives meanwhile only flags a rerun, which is dropped with the key below. That is
        // right here, since the account it would sync is gone.
        String key = studentId + ":" + provider;
        if (inFlight.putIfAbsent(key, SyncState.RUNNING) != null) {
            throw new CalendarSyncInProgressException(provider + " sync in progress for student " + studentId);
        }
        try {
            return calendarAccountRepository.findByOwnerIdAndProvider(studentId, provider)
                    .map(account -> doDisconnect(studentId, account, removeEvents));
        } finally {
            inFlight.remove(key);
        }
    }

    private CalendarDisconnectResult doDisconnect(Long studentId, CalendarAccount account, boolean removeEvents) {
        CalendarProvider provider = account.getProvider();
        CalendarStrategy strategy = strategyRegistry.forProvider(provider);
        int removed = 0;
        int notRemoved = 0;

        if (removeEvents) {
            List<ExportedEvent> pushed = exportRepository
                    .findTopByOwnerIdAndProviderAndAccountEmailOrderByExportedAtDesc(studentId, provider, account.getEmail())
                    .map(CalendarExport::getEvents)
                    .orElse(Collections.emptyList());
            if (pushed != null && !pushed.isEmpty()) {
                List<ExportedEvent> remaining = new ArrayList<>(pushed);
                String accessToken = accessTokenForRemoval(account, strategy);
                if (accessToken != null) {
                    remaining.clear();
                    for (ExportedEvent event : pushed) {
                        if (event.getExternalEventId() == null) continue;
                        try {
                            strategy.deleteEvent(accessToken, event.getExternalEventId());
                            removed++;
                        } catch (Exception e) {
                            remaining.add(event);
                            logger.warn("Failed to remove {} calendar event {} on disconnect: {}",
                                    provider, event.getEventKey(), e.getMessage());
                        }
                    }
                }
                notRemoved = remaining.size();
                saveSnapshot(studentId, provider, account.getEmail(), remaining);
            }
        }

        // Best effort: the account row is what onTrack uses, and it goes either way.
        try {
            strategy.revoke(account.getRefreshToken());
        } catch (Exception e) {
            logger.warn("Revoking {} access failed for student {}: {}", provider, studentId, e.getMessage());
        }
        calendarAccountRepository.delete(account);

        logger.info("{} calendar disconnected for student {}: {} events removed, {} left in place",
                provider, studentId, removed, notRemoved);
        return new CalendarDisconnectResult(removed, notRemoved);
    }

    /**
     * An access token good enough to delete with, or null when there is none to be had. A disconnect
     * must not fail because the provider is down or access was already revoked: the student asked
     * to be disconnected, and the events left behind are reported back instead.
     */
    private String accessTokenForRemoval(CalendarAccount account, CalendarStrategy strategy) {
        if (account.isNeedsReconnect()) {
            return null;
        }
        try {
            return resolveAccessToken(account, strategy);
        } catch (CalendarReconnectRequiredException | CalendarSyncUnavailableException e) {
            logger.warn("Cannot remove {} events on disconnect for student {}: {}",
                    account.getProvider(), account.getOwnerId(), e.getMessage());
            return null;
        }
    }

    /**
     * Saves a new snapshot for one account and drops the ones it replaces, since only the newest
     * per account is ever read. A failed prune leaves extra rows, not wrong ones, so it must not
     * fail a sync or disconnect that already reached the provider.
     */
    private void saveSnapshot(Long studentId, CalendarProvider provider, String accountEmail, List<ExportedEvent> events) {
        CalendarExport export = new CalendarExport();
        export.setOwnerId(studentId);
        export.setProvider(provider);
        export.setAccountEmail(accountEmail);
        export.setExportedAt(Instant.now());
        export.setEvents(events);
        CalendarExport saved = exportRepository.save(export);
        try {
            exportRepository.deleteOlderSnapshots(studentId, provider, accountEmail, saved.getId());
        } catch (RuntimeException e) {
            logger.warn("Pruning old {} snapshots failed for student {}: {}", provider, studentId, e.getMessage());
        }
    }

    /** Every external calendar the student has connected, including ones waiting on a reconnect. */
    public List<CalendarAccountStatus> accountStatuses(Long studentId) {
        return calendarAccountRepository.findByOwnerId(studentId).stream()
                .map(a -> new CalendarAccountStatus(a.getProvider(), a.getEmail(), a.isNeedsReconnect()))
                .toList();
    }

    public boolean isAccountConnectedForEmail(Long studentId, CalendarProvider provider, String email) {
        return calendarAccountRepository
                .findByOwnerIdAndProvider(studentId, provider)
                .map(account -> !account.isNeedsReconnect() && email.equalsIgnoreCase(account.getEmail()))
                .orElse(false);
    }

    private String resolveAccessToken(CalendarAccount account, CalendarStrategy strategy) {
        if (account.getExpiresAt().isBefore(Instant.now().plus(REFRESH_MARGIN))) {
            try {
                OAuthTokens refreshed = strategy.refreshAccessToken(account.getRefreshToken());
                account.setAccessToken(refreshed.accessToken());
                if (refreshed.refreshToken() != null) {
                    account.setRefreshToken(refreshed.refreshToken());
                }
                account.setExpiresAt(refreshed.expiresAt());
                calendarAccountRepository.save(account);
            } catch (CalendarAuthorizationRevokedException e) {
                // Flagged, not deleted: the app reads the flag to tell the student which account
                // to reconnect. A background sync has no other way to reach them.
                logger.warn("Refresh token revoked/expired for {} account of student {}, flagging for reconnect",
                        account.getProvider(), account.getOwnerId());
                account.setNeedsReconnect(true);
                calendarAccountRepository.save(account);
                throw new CalendarReconnectRequiredException(account.getProvider()
                        + " calendar authorization expired for student " + account.getOwnerId());
            } catch (Exception e) {
                // Anything else (a provider 5xx, a timeout) says nothing about the refresh token,
                // so the account is kept and the next sync tries again.
                logger.warn("Token refresh failed for {} account of student {}, keeping account: {}",
                        account.getProvider(), account.getOwnerId(), e.getMessage());
                throw new CalendarSyncUnavailableException(account.getProvider()
                        + " token refresh failed", e);
            }
        }

        return account.getAccessToken();
    }
}
