package org.tracker.gpatracker.calendar.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.tracker.gpatracker.calendar.dto.OAuthTokens;
import org.tracker.gpatracker.calendar.model.AbstractCalendarEvent;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CalendarSyncServiceTest {

    private static final Long STUDENT = 1L;

    private CalendarEventsRepository eventsRepository;
    private CalendarExportRepository exportRepository;
    private CalendarAccountRepository accountRepository;
    private FakeStrategy google;
    private FakeStrategy microsoft;
    private CalendarSyncService service;

    @BeforeEach
    void setUp() {
        eventsRepository = mock(CalendarEventsRepository.class);
        exportRepository = mock(CalendarExportRepository.class);
        accountRepository = mock(CalendarAccountRepository.class);
        google = new FakeStrategy(CalendarProvider.GOOGLE);
        microsoft = new FakeStrategy(CalendarProvider.MICROSOFT);
        service = new CalendarSyncService(eventsRepository, exportRepository, accountRepository,
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
        assertThat(savedExport().getEvents()).extracting(ExportedEvent::getExternalEventId)
                .containsExactly("g1");
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
    @DisplayName("a failed refresh removes the stale account and asks for a reconnect")
    void failedRefreshRemovesAccount() {
        CalendarAccount account = connect(CalendarProvider.GOOGLE, "a@gmail.com");
        account.setExpiresAt(Instant.now().minusSeconds(60));
        projection(event("CS101", "Midterm", "2026-11-01"));
        google.failRefresh = true;

        assertThatThrownBy(() -> service.sync(STUDENT, CalendarProvider.GOOGLE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reconnect");
        verify(accountRepository).delete(account);
        verify(exportRepository, never()).save(any());
    }

    // ------------------------------------------------------------------ fixtures

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
        when(eventsRepository.findByOwnerId(STUDENT)).thenReturn(Optional.of(projection));
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

    private static ExportedEvent exported(String course, String name, String date, String externalId) {
        ExportedEvent e = new ExportedEvent();
        e.setCourseCode(course);
        e.setAssessmentName(name);
        e.setDueDate(date);
        e.setEventKey(AbstractCalendarEvent.buildEventKey(course, name, date));
        e.setExternalEventId(externalId);
        return e;
    }

    /** Records calls instead of making them. External ids are "PROVIDER-eventKey". */
    private static class FakeStrategy implements CalendarStrategy {

        private final CalendarProvider provider;
        final List<String> created = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        final List<String> tokensUsed = new ArrayList<>();
        boolean failRefresh;

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
                throw new IllegalStateException("invalid_grant");
            }
            return new OAuthTokens("fresh-access", "rotated-refresh", Instant.now().plusSeconds(3600), null);
        }

        @Override
        public String createEvent(String accessToken, InternalCalendarEvent event) {
            tokensUsed.add(accessToken);
            created.add(event.getEventKey());
            return provider + "-" + event.getEventKey();
        }

        @Override
        public void updateEvent(String accessToken, String externalEventId, InternalCalendarEvent event) {
            tokensUsed.add(accessToken);
        }

        @Override
        public void deleteEvent(String accessToken, String externalEventId) {
            tokensUsed.add(accessToken);
            deleted.add(externalEventId);
        }
    }
}
