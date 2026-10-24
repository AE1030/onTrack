package org.tracker.gpatracker.calendar.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.tracker.gpatracker.calendar.event.CalendarProjectionUpdatedEvent;
import org.tracker.gpatracker.support.ContainerIntegrationBase;
import org.tracker.gpatracker.tenancy.UserContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * The two things {@link CalendarSyncListener} depends on that no unit test can see: that
 * {@code @Async} is actually applied, and that the publishing request's tenant reaches the worker.
 * Lose either and nothing fails to compile; syncs just block requests again or throw for want of
 * an owner, and the per-account catch in {@code syncAll} would log that and carry on.
 */
@SpringBootTest
@ActiveProfiles("test")
class CalendarSyncListenerIntegrationTest extends ContainerIntegrationBase {

    private static final Long USER = 41L;
    private static final Long STUDENT = 42L;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @MockitoBean
    private CalendarSyncService calendarSyncService;

    @Test
    @DisplayName("sync runs on a background thread with the publisher's tenant bound")
    void runsAsyncWithTenant() throws Exception {
        CompletableFuture<String> threadName = new CompletableFuture<>();
        CompletableFuture<Long> ownerSeen = new CompletableFuture<>();
        doAnswer(inv -> {
            threadName.complete(Thread.currentThread().getName());
            ownerSeen.complete(UserContext.ownerIdOrNull());
            return null;
        }).when(calendarSyncService).syncAll(anyLong());

        UserContext.runAs(USER, STUDENT,
                () -> eventPublisher.publishEvent(new CalendarProjectionUpdatedEvent(STUDENT)));

        assertThat(threadName.get(5, TimeUnit.SECONDS))
                .as("the async executor's thread prefix, not the test thread")
                .startsWith("async-");
        assertThat(ownerSeen.get(5, TimeUnit.SECONDS)).isEqualTo(STUDENT);
    }
}
