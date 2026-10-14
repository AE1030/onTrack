package org.tracker.gpatracker.calendar.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.tracker.gpatracker.calendar.dto.OAuthTokens;
import org.tracker.gpatracker.calendar.model.CalendarAccount;
import org.tracker.gpatracker.calendar.model.CalendarEvents;
import org.tracker.gpatracker.calendar.model.CalendarEvents.InternalCalendarEvent;
import org.tracker.gpatracker.calendar.model.CalendarExport;
import org.tracker.gpatracker.calendar.model.CalendarExport.ExportedEvent;
import org.tracker.gpatracker.calendar.model.CalendarProvider;
import org.tracker.gpatracker.calendar.repository.CalendarAccountRepository;
import org.tracker.gpatracker.calendar.repository.CalendarEventsRepository;
import org.tracker.gpatracker.calendar.repository.CalendarExportRepository;
import org.tracker.gpatracker.calendar.strategy.CalendarStrategy;
import org.tracker.gpatracker.calendar.strategy.CalendarStrategyRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private enum SyncState { RUNNING, RERUN_REQUESTED }

    /**
     * Keyed by student and provider. In-memory, so it only coordinates syncs within one instance;
     * two instances can still sync the same account at once.
     */
    private final ConcurrentHashMap<String, SyncState> inFlight = new ConcurrentHashMap<>();

    private final CalendarEventsRepository calendarEventsRepository;
    private final CalendarExportRepository exportRepository;
    private final CalendarAccountRepository calendarAccountRepository;
    private final CalendarStrategyRegistry strategyRegistry;

    public CalendarSyncService(
            CalendarEventsRepository calendarEventsRepository,
            CalendarExportRepository exportRepository,
            CalendarAccountRepository calendarAccountRepository,
            CalendarStrategyRegistry strategyRegistry
    ) {
        this.calendarEventsRepository = calendarEventsRepository;
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
     * @throws IllegalStateException if the account is not connected or its authorization has lapsed;
     *         the controller turns this into a reconnect prompt
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
                .orElseThrow(() -> new IllegalStateException(provider + " calendar not connected for student " + studentId));
        CalendarStrategy strategy = strategyRegistry.forProvider(provider);

        // Step 1: Get current projection
        CalendarEvents calendarEvents = calendarEventsRepository.findByOwnerId(studentId)
                .orElseThrow(() -> new IllegalStateException("No calendar events found for student " + studentId));

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
        deleteRemovedEvents(strategy, accessToken, toDelete, previousMap);

        List<ExportedEvent> newExportEvents = new ArrayList<>();

        // Keep existing events that are still current
        for (ExportedEvent prev : previousEvents) {
            if (!toDelete.contains(prev.getEventKey())) {
                newExportEvents.add(prev);
            }
        }

        // Add new events
        createNewEvents(strategy, accessToken, toAdd, currentMap, newExportEvents);

        // Step 6: Save new export snapshot
        CalendarExport newExport = new CalendarExport();
        newExport.setOwnerId(studentId);
        newExport.setProvider(provider);
        newExport.setAccountEmail(account.getEmail());
        newExport.setExportedAt(Instant.now());
        newExport.setEvents(newExportEvents);
        exportRepository.save(newExport);

        logger.info("{} calendar sync completed for student {}: {} added, {} deleted, {} kept",
                provider, studentId, toAdd.size(), toDelete.size(), newExportEvents.size() - toAdd.size());
    }

    private void deleteRemovedEvents(CalendarStrategy strategy, String accessToken,
                                     Set<String> toDelete, Map<String, ExportedEvent> previousMap) {
        for (String key : toDelete) {
            ExportedEvent prev = previousMap.get(key);
            if (prev.getExternalEventId() == null) continue;
            try {
                strategy.deleteEvent(accessToken, prev.getExternalEventId());
                logger.info("Deleted {} calendar event: {}", strategy.provider(), key);
            } catch (Exception e) {
                logger.error("Failed to delete {} calendar event {}: {}", strategy.provider(), key, e.getMessage());
            }
        }
    }

    private void createNewEvents(CalendarStrategy strategy, String accessToken, Set<String> toAdd,
                                 Map<String, InternalCalendarEvent> currentMap,
                                 List<ExportedEvent> newExportEvents) {
        for (String key : toAdd) {
            InternalCalendarEvent current = currentMap.get(key);
            try {
                String externalEventId = strategy.createEvent(accessToken, current);
                ExportedEvent exported = new ExportedEvent();
                exported.setCourseCode(current.getCourseCode());
                exported.setAssessmentName(current.getAssessmentName());
                exported.setDueDate(current.getDueDate());
                exported.setStartTime(current.getStartTime());
                exported.setEndTime(current.getEndTime());
                exported.setAllDay(current.isAllDay());
                exported.setEventKey(current.getEventKey());
                exported.setExternalEventId(externalEventId);
                newExportEvents.add(exported);
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

    public boolean isAccountConnectedForEmail(Long studentId, CalendarProvider provider, String email) {
        return calendarAccountRepository
                .findByOwnerIdAndProvider(studentId, provider)
                .map(account -> email.equalsIgnoreCase(account.getEmail()))
                .orElse(false);
    }

    private String resolveAccessToken(CalendarAccount account, CalendarStrategy strategy) {
        if (account.getExpiresAt().isBefore(Instant.now())) {
            try {
                OAuthTokens refreshed = strategy.refreshAccessToken(account.getRefreshToken());
                account.setAccessToken(refreshed.accessToken());
                if (refreshed.refreshToken() != null) {
                    account.setRefreshToken(refreshed.refreshToken());
                }
                account.setExpiresAt(refreshed.expiresAt());
                calendarAccountRepository.save(account);
            } catch (Exception e) {
                logger.warn("Refresh token revoked/expired for {} account of student {}, removing stale account",
                        account.getProvider(), account.getOwnerId());
                calendarAccountRepository.delete(account);
                throw new IllegalStateException(account.getProvider()
                        + " calendar authorization expired. Please reconnect your account.");
            }
        }

        return account.getAccessToken();
    }
}
