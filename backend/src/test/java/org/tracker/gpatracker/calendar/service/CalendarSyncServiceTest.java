package org.tracker.gpatracker.calendar.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.tracker.gpatracker.calendar.dto.CalendarAccountStatus;
import org.tracker.gpatracker.calendar.dto.OAuthTokens;
import org.tracker.gpatracker.calendar.exception.CalendarAuthorizationRevokedException;
import org.tracker.gpatracker.calendar.exception.CalendarReconnectRequiredException;
import org.tracker.gpatracker.calendar.exception.CalendarSyncUnavailableException;
import org.tracker.gpatracker.calendar.model.AbstractCalendarEvent;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalendarSyncServiceTest {

    private static final Long STUDENT = 1L;

    private CalendarProjectionService projectionService;
    private CalendarExportRepository exportRepository;
    private CalendarAccountRepository accountRepository;
    private FakeStrategy google;
    private FakeStrategy microsoft;
    private CalendarSyncService service;

    @BeforeEach
    void setUp() {
        projectionService = mock(CalendarProjectionService.class);
        exportRepository = mock(CalendarExportRepository.class);
        when(exportRepository.save(any())).thenAnswer(inv -> {
            CalendarExport export = inv.getArgument(0);
            export.setId("new-snapshot");
            return export;
        });
        accountRepository = mock(CalendarAccountRepository.class);
        google = new FakeStrategy(CalendarProvider.GOOGLE);
        microsoft = new FakeStrategy(CalendarProvider.MICROSOFT);
        service = new CalendarSyncService(projectionService, exportRepository, accountRepository,
                new CalendarStrategyRegistry(List.of(google, microsoft)));
    }

    @Test
    @DisplayName("new events are created and their external ids recorded in a snapshot for that account")
    void createsAndRecordsSnapshot() {
        connect(CalendarProvider.GOOGLE, "a@gmail.com");
        projection(event("CS101", "Midterm", "2026-11-01"));

        service.sync(STUDENT, CalendarProvider.GOOGLE);

        assertThat(google.created).containsExactly("CS101|Midterm|2026-11-01");
        CalendarExport saved = savedExport();
        assertThat(saved.getProvider()).isEqualTo(CalendarProvider.GOOGLE);
        assertThat(saved.getAccountEmail()).isEqualTo("a@gmail.com");
        assertThat(saved.getEvents()).extracting(ExportedEvent::getExternalEventId)
                .containsExactly("GOOGLE-CS101|Midterm|2026-11-01");
    }

    @Test
    @DisplayName("events gone from the projection are deleted; unchanged ones are left alone")
    void deletesRemovedKeepsUnchanged() {
        connect(CalendarProvider.GOOGLE, "a@gmail.com");
        projection(event("CS101", "Midterm", "2026-11-01"));
        lastExport(CalendarProvider.GOOGLE, "a@gmail.com",
                exported("CS101", "Midterm", "2026-11-01", "g1"),
                exported("CS101", "Quiz", "2026-10-20", "g2"));

        service.sync(STUDENT, CalendarProvider.GOOGLE);

        assertThat(google.deleted).containsExactly("g2");
        assertThat(google.created).isEmpty();
        assertThat(google.updated).isEmpty();
        assertThat(savedExport().getEvents()).extracting(ExportedEvent::getExternalEventId)
                .containsExactly("g1");
    }

    @Test
    @DisplayName("a failed delete stays in the snapshot and is retried on the next sync")
    void failedDeleteIsRetried() {
        connect(CalendarProvider.GOOGLE, "a@gmail.com");
        projection(event("CS101", "Midterm", "2026-11-01"));
        ExportedEvent kept = exported("CS101", "Midterm", "2026-11-01", "g1");
        ExportedEvent removed = exported("CS101", "Quiz", "2026-10-20", "g2");
        lastExport(CalendarProvider.GOOGLE, "a@gmail.com", kept, removed);
        google.failDelete = true;

        service.sync(STUDENT, CalendarProvider.GOOGLE);

        CalendarExport afterFailure = savedExport();
        assertThat(afterFailure.getEvents()).containsExactlyInAnyOrder(kept, removed);

        // Next sync diffs against the snapshot that still holds g2, and the delete goes through.
        google.failDelete = false;
        when(exportRepository.findTopByOwnerIdAndProviderAndAccountEmailOrderByExportedAtDesc(
                eq(STUDENT), eq(CalendarProvider.GOOGLE), eq("a@gmail.com"))).thenReturn(Optional.of(afterFailure));
        service.sync(STUDENT, CalendarProvider.GOOGLE);

        assertThat(google.deleted).containsExactly("g2");
        ArgumentCaptor<CalendarExport> captor = ArgumentCaptor.forClass(CalendarExport.class);
        verify(exportRepository, times(2)).save(captor.capture());
        assertThat(captor.getValue().getEvents()).containsExactly(kept);
    }

    @Test
    @DisplayName("an event whose time or location changed but kept its key is updated in place")
    void changedContentIsUpdated() {
        connect(CalendarProvider.GOOGLE, "a@gmail.com");
        InternalCalendarEvent before = event("CS101", "Midterm", "2026-11-01");
        InternalCalendarEvent after = event("CS101", "Midterm", "2026-11-01");
        after.setAllDay(false);
        after.setStartTime("10:00");
        after.setEndTime("11:30");
        after.setLocation("EITC E2 130");
        projection(after);
        lastExport(CalendarProvider.GOOGLE, "a@gmail.com", exported(before, "g1"));

        service.sync(STUDENT, CalendarProvider.GOOGLE);

        assertThat(google.updated).containsExactly("g1");
        assertThat(google.created).isEmpty();
        assertThat(google.deleted).isEmpty();
        ExportedEvent saved = savedExport().getEvents().get(0);
        assertThat(saved.getExternalEventId()).isEqualTo("g1");
        assertThat(saved.getStartTime()).isEqualTo("10:00");
        assertThat(saved.getContentHash()).isEqualTo(CalendarSyncService.contentHash(after));
    }

    @Test
    @DisplayName("a snapshot entry from before hashes were stored is updated once, which backfills its hash")
    void missingHashIsBackfilled() {
        connect(CalendarProvider.GOOGLE, "a@gmail.com");
        InternalCalendarEvent current = event("CS101", "Midterm", "2026-11-01");
        projection(current);
        ExportedEvent legacy = exported(current, "g1");
        legacy.setContentHash(null);
        lastExport(CalendarProvider.GOOGLE, "a@gmail.com", legacy);

        service.sync(STUDENT, CalendarProvider.GOOGLE);

        assertThat(google.updated).containsExactly("g1");
        assertThat(savedExport().getEvents().get(0).getContentHash())
                .isEqualTo(CalendarSyncService.contentHash(current));
    }

    @Test
    @DisplayName("a failed update keeps the old snapshot entry, so the next sync retries it")
    void failedUpdateIsRetried() {
        connect(CalendarProvider.GOOGLE, "a@gmail.com");
        InternalCalendarEvent before = event("CS101", "Midterm", "2026-11-01");
        InternalCalendarEvent after = event("CS101", "Midterm", "2026-11-01");
        after.setDescription("Chapters 1 to 4");
        projection(after);
        ExportedEvent prev = exported(before, "g1");
        lastExport(CalendarProvider.GOOGLE, "a@gmail.com", prev);
        google.failUpdate = true;

        service.sync(STUDENT, CalendarProvider.GOOGLE);

        assertThat(savedExport().getEvents()).containsExactly(prev);
    }

    @Test
    @DisplayName("each provider diffs against its own snapshot, so Google's history does not hide events from Microsoft")
    void providersDoNotShareSnapshots() {
        connect(CalendarProvider.GOOGLE, "a@gmail.com");
        connect(CalendarProvider.MICROSOFT, "a@outlook.com");
        projection(event("CS101", "Midterm", "2026-11-01"));
        lastExport(CalendarProvider.GOOGLE, "a@gmail.com", exported("CS101", "Midterm", "2026-11-01", "g1"));

        service.syncAll(STUDENT);

        assertThat(google.created).isEmpty();
        assertThat(microsoft.created).containsExactly("CS101|Midterm|2026-11-01");
    }

    @Test
    @DisplayName("one provider failing does not stop the others")
    void oneFailureDoesNotBlockOthers() {
        // A revoked refresh token makes sync() throw, unlike a single failed create, which is
        // caught per event. This is the path syncAll's per-account catch exists for.
        connect(CalendarProvider.GOOGLE, "a@gmail.com").setExpiresAt(Instant.now().minusSeconds(60));
        connect(CalendarProvider.MICROSOFT, "a@outlook.com");
        projection(event("CS101", "Midterm", "2026-11-01"));
        google.failRefresh = true;

        service.syncAll(STUDENT);

        assertThat(microsoft.created).containsExactly("CS101|Midterm|2026-11-01");
    }

    @Test
    @DisplayName("an expired token is refreshed, and a rotated refresh token is persisted")
    void refreshesExpiredToken() {
        CalendarAccount account = connect(CalendarProvider.MICROSOFT, "a@outlook.com");
        account.setExpiresAt(Instant.now().minusSeconds(60));
        account.setRefreshToken("old-refresh");
        projection(event("CS101", "Midterm", "2026-11-01"));

        service.sync(STUDENT, CalendarProvider.MICROSOFT);

        assertThat(account.getAccessToken()).isEqualTo("fresh-access");
        assertThat(account.getRefreshToken()).isEqualTo("rotated-refresh");
        verify(accountRepository).save(account);
        assertThat(microsoft.tokensUsed).containsOnly("fresh-access");
    }

    @Test
    @DisplayName("a revoked refresh token flags the account for reconnect instead of deleting it")
    void revokedRefreshFlagsAccount() {
        CalendarAccount account = connect(CalendarProvider.GOOGLE, "a@gmail.com");
        account.setExpiresAt(Instant.now().minusSeconds(60));
        projection(event("CS101", "Midterm", "2026-11-01"));
        google.failRefresh = true;

        assertThatThrownBy(() -> service.sync(STUDENT, CalendarProvider.GOOGLE))
                .isInstanceOf(CalendarReconnectRequiredException.class);
        assertThat(account.isNeedsReconnect()).isTrue();
        verify(accountRepository).save(account);
        verify(accountRepository, never()).delete(any());
        verify(exportRepository, never()).save(any());
        assertThat(service.accountStatuses(STUDENT))
                .containsExactly(new CalendarAccountStatus(CalendarProvider.GOOGLE, "a@gmail.com", true));
    }

    @Test
    @DisplayName("a flagged account is skipped by background syncs and refused by a direct one")
    void flaggedAccountIsSkipped() {
        connect(CalendarProvider.GOOGLE, "a@gmail.com").setNeedsReconnect(true);
        connect(CalendarProvider.MICROSOFT, "a@outlook.com");
        projection(event("CS101", "Midterm", "2026-11-01"));

        service.syncAll(STUDENT);

        assertThat(google.tokensUsed).isEmpty();
        assertThat(microsoft.created).containsExactly("CS101|Midterm|2026-11-01");
        assertThatThrownBy(() -> service.sync(STUDENT, CalendarProvider.GOOGLE))
                .isInstanceOf(CalendarReconnectRequiredException.class);
        // So /sync answers with the connect prompt even though the email matches.
        assertThat(service.isAccountConnectedForEmail(STUDENT, CalendarProvider.GOOGLE, "a@gmail.com")).isFalse();
    }

    @Test
    @DisplayName("a token inside the refresh margin is refreshed before the sync; one well clear of it is not")
    void refreshesNearExpiry() {
        CalendarAccount soon = connect(CalendarProvider.GOOGLE, "a@gmail.com");
        soon.setExpiresAt(Instant.now().plusSeconds(60));
        CalendarAccount later = connect(CalendarProvider.MICROSOFT, "a@outlook.com");
        later.setExpiresAt(Instant.now().plus(CalendarSyncService.REFRESH_MARGIN).plusSeconds(600));
        projection(event("CS101", "Midterm", "2026-11-01"));

        service.syncAll(STUDENT);

        assertThat(google.tokensUsed).containsOnly("fresh-access");
        assertThat(microsoft.tokensUsed).containsOnly("access");
    }

    @Test
    @DisplayName("a refresh that fails for any other reason keeps the account and does not ask for a reconnect")
    void transientRefreshFailureKeepsAccount() {
        CalendarAccount account = connect(CalendarProvider.GOOGLE, "a@gmail.com");
        account.setExpiresAt(Instant.now().minusSeconds(60));
        projection(event("CS101", "Midterm", "2026-11-01"));
        google.refreshFailure = new RuntimeException("503 Service Unavailable");

        assertThatThrownBy(() -> service.sync(STUDENT, CalendarProvider.GOOGLE))
                .isInstanceOf(CalendarSyncUnavailableException.class);
        verify(accountRepository, never()).delete(any());
        verify(exportRepository, never()).save(any());

        // The next sync, once the provider is back, goes through on the same account.
        google.refreshFailure = null;
        service.sync(STUDENT, CalendarProvider.GOOGLE);
        assertThat(google.created).containsExactly("CS101|Midterm|2026-11-01");
    }

    @Test
    @DisplayName("a sync requested while one is running is rerun afterwards, not dropped")
    void midSyncRequestTriggersRerun() throws Exception {
        connect(CalendarProvider.GOOGLE, "a@gmail.com");
        projection(event("CS101", "Midterm", "2026-11-01"));
        CountDownLatch insideFirstSync = new CountDownLatch(1);
        CountDownLatch releaseFirstSync = new CountDownLatch(1);
        google.onCreate = () -> {
            if (insideFirstSync.getCount() > 0) {
                insideFirstSync.countDown();
                await(releaseFirstSync);
            }
        };

        Thread first = new Thread(() -> service.sync(STUDENT, CalendarProvider.GOOGLE));
        first.start();
        assertThat(insideFirstSync.await(5, TimeUnit.SECONDS)).isTrue();

        // Returns straight away: the running sync owns the account and takes the rerun.
        service.sync(STUDENT, CalendarProvider.GOOGLE);
        verify(exportRepository, never()).save(any());

        releaseFirstSync.countDown();
        first.join(5000);

        verify(exportRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("a sync that throws releases the account, so the next request runs")
    void failedSyncReleasesAccount() {
        projection(event("CS101", "Midterm", "2026-11-01"));

        assertThatThrownBy(() -> service.sync(STUDENT, CalendarProvider.GOOGLE))
                .isInstanceOf(CalendarReconnectRequiredException.class);

        connect(CalendarProvider.GOOGLE, "a@gmail.com");
        service.sync(STUDENT, CalendarProvider.GOOGLE);

        assertThat(google.created).containsExactly("CS101|Midterm|2026-11-01");
    }

    @Test
    @DisplayName("a student with nothing to export syncs normally and gets an empty snapshot")
    void emptyProjectionIsNotAnError() {
        connect(CalendarProvider.GOOGLE, "a@gmail.com");
        projection();

        service.sync(STUDENT, CalendarProvider.GOOGLE);

        assertThat(google.created).isEmpty();
        assertThat(savedExport().getEvents()).isEmpty();
    }

    @Test
    @DisplayName("an empty projection against an existing snapshot removes every exported event")
    void emptyProjectionDeletesEverything() {
        connect(CalendarProvider.GOOGLE, "a@gmail.com");
        projection();
        lastExport(CalendarProvider.GOOGLE, "a@gmail.com",
                exported("CS101", "Midterm", "2026-11-01", "g1"),
                exported("CS101", "Quiz", "2026-10-20", "g2"));

        service.sync(STUDENT, CalendarProvider.GOOGLE);

        assertThat(google.deleted).containsExactlyInAnyOrder("g1", "g2");
        assertThat(savedExport().getEvents()).isEmpty();
    }

    @Test
    @DisplayName("a provider with no strategy fails without asking for a reconnect")
    void missingStrategyIsNotAReconnect() {
        CalendarSyncService googleOnly = new CalendarSyncService(projectionService, exportRepository,
                accountRepository, new CalendarStrategyRegistry(List.of(google)));
        connect(CalendarProvider.MICROSOFT, "a@outlook.com");
        projection(event("CS101", "Midterm", "2026-11-01"));

        assertThatThrownBy(() -> googleOnly.sync(STUDENT, CalendarProvider.MICROSOFT))
                .isNotInstanceOf(CalendarReconnectRequiredException.class);
    }

    @Test
    @DisplayName("after saving a snapshot, older ones for that same account are pruned and nothing else")
    void prunesOlderSnapshotsForThisAccountOnly() {
        connect(CalendarProvider.GOOGLE, "a@gmail.com");
        projection(event("CS101", "Midterm", "2026-11-01"));

        service.sync(STUDENT, CalendarProvider.GOOGLE);

        verify(exportRepository).deleteOlderSnapshots(STUDENT, CalendarProvider.GOOGLE, "a@gmail.com", "new-snapshot");
    }

    @Test
    @DisplayName("a failed prune is logged and does not fail a sync that already pushed")
    void pruneFailureDoesNotFailSync() {
        connect(CalendarProvider.GOOGLE, "a@gmail.com");
        projection(event("CS101", "Midterm", "2026-11-01"));
        when(exportRepository.deleteOlderSnapshots(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("lock timeout"));

        service.sync(STUDENT, CalendarProvider.GOOGLE);

        assertThat(google.created).containsExactly("CS101|Midterm|2026-11-01");
    }

    // ------------------------------------------------------------------ fixtures

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final List<CalendarAccount> accounts = new ArrayList<>();

    private CalendarAccount connect(CalendarProvider provider, String email) {
        CalendarAccount account = new CalendarAccount();
        account.setOwnerId(STUDENT);
        account.setProvider(provider);
        account.setEmail(email);
        account.setAccessToken("access");
        account.setExpiresAt(Instant.now().plusSeconds(3600));
        accounts.add(account);
        when(accountRepository.findByOwnerIdAndProvider(STUDENT, provider)).thenReturn(Optional.of(account));
        when(accountRepository.findByOwnerId(STUDENT)).thenReturn(accounts);
        return account;
    }

    private void projection(InternalCalendarEvent... events) {
        CalendarEvents projection = new CalendarEvents();
        projection.setEvents(List.of(events));
        when(projectionService.getCalendarEvents(STUDENT)).thenReturn(Optional.of(projection));
    }

    private void lastExport(CalendarProvider provider, String email, ExportedEvent... events) {
        CalendarExport export = new CalendarExport();
        export.setProvider(provider);
        export.setAccountEmail(email);
        export.setEvents(List.of(events));
        when(exportRepository.findTopByOwnerIdAndProviderAndAccountEmailOrderByExportedAtDesc(
                eq(STUDENT), eq(provider), eq(email))).thenReturn(Optional.of(export));
    }

    private CalendarExport savedExport() {
        ArgumentCaptor<CalendarExport> captor = ArgumentCaptor.forClass(CalendarExport.class);
        verify(exportRepository).save(captor.capture());
        return captor.getValue();
    }

    private static InternalCalendarEvent event(String course, String name, String date) {
        InternalCalendarEvent e = new InternalCalendarEvent();
        e.setCourseCode(course);
        e.setAssessmentName(name);
        e.setDueDate(date);
        e.setAllDay(true);
        e.setEventKey(AbstractCalendarEvent.buildEventKey(course, name, date));
        return e;
    }

    /** A snapshot entry already in sync with {@link #event(String, String, String)}. */
    private static ExportedEvent exported(String course, String name, String date, String externalId) {
        return exported(event(course, name, date), externalId);
    }

    private static ExportedEvent exported(InternalCalendarEvent pushed, String externalId) {
        ExportedEvent e = new ExportedEvent();
        e.setCourseCode(pushed.getCourseCode());
        e.setAssessmentName(pushed.getAssessmentName());
        e.setDueDate(pushed.getDueDate());
        e.setStartTime(pushed.getStartTime());
        e.setEndTime(pushed.getEndTime());
        e.setAllDay(pushed.isAllDay());
        e.setEventKey(pushed.getEventKey());
        e.setExternalEventId(externalId);
        e.setContentHash(CalendarSyncService.contentHash(pushed));
        return e;
    }

    /** Records calls instead of making them. External ids are "PROVIDER-eventKey". */
    private static class FakeStrategy implements CalendarStrategy {

        private final CalendarProvider provider;
        final List<String> created = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        final List<String> updated = new ArrayList<>();
        final List<String> tokensUsed = new ArrayList<>();
        /** Makes refresh fail the way a revoked token does. */
        boolean failRefresh;
        /** Thrown from refresh when set, for failures that say nothing about the token. */
        RuntimeException refreshFailure;
        boolean failUpdate;
        boolean failDelete;
        /** Runs inside createEvent, so a test can hold a sync open mid-flight. */
        Runnable onCreate = () -> { };

        FakeStrategy(CalendarProvider provider) {
            this.provider = provider;
        }

        @Override
        public CalendarProvider provider() {
            return provider;
        }

        @Override
        public OAuthTokens refreshAccessToken(String refreshToken) {
            if (failRefresh) {
                throw new CalendarAuthorizationRevokedException("invalid_grant", null);
            }
            if (refreshFailure != null) {
                throw refreshFailure;
            }
            return new OAuthTokens("fresh-access", "rotated-refresh", Instant.now().plusSeconds(3600), null);
        }

        @Override
        public String createEvent(String accessToken, InternalCalendarEvent event) {
            onCreate.run();
            tokensUsed.add(accessToken);
            created.add(event.getEventKey());
            return provider + "-" + event.getEventKey();
        }

        @Override
        public void updateEvent(String accessToken, String externalEventId, InternalCalendarEvent event) {
            if (failUpdate) {
                throw new IllegalStateException("provider unavailable");
            }
            tokensUsed.add(accessToken);
            updated.add(externalEventId);
        }

        @Override
        public void deleteEvent(String accessToken, String externalEventId) {
            if (failDelete) {
                throw new IllegalStateException("provider unavailable");
            }
            tokensUsed.add(accessToken);
            deleted.add(externalEventId);
        }
    }
}
