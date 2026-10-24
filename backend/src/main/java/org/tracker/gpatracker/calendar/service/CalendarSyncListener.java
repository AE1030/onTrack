package org.tracker.gpatracker.calendar.service;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.tracker.gpatracker.calendar.event.CalendarProjectionUpdatedEvent;

/**
 * Pushes projection changes to every connected external calendar, off the request thread.
 *
 * <p>The sync makes one HTTP call per changed event per provider, which has no business holding up
 * the assessment-table save that triggered it. {@code @Async} runs this on the executor from
 * {@code AsyncConfig}, whose {@code TenantTaskDecorator} carries the publishing request's tenant
 * across; without it every repository call here would throw for want of an owner.
 *
 * <p>A plain {@code @EventListener}, not {@code @TransactionalEventListener}: the publishers are
 * not transactional, and a transactional listener with no transaction to wait for is silently
 * skipped. Revisit if {@code AssessmentTableService} ever gains a transaction.
 */
@Component
public class CalendarSyncListener {

    private final CalendarSyncService calendarSyncService;

    public CalendarSyncListener(CalendarSyncService calendarSyncService) {
        this.calendarSyncService = calendarSyncService;
    }

    @Async
    @EventListener
    public void onProjectionUpdated(CalendarProjectionUpdatedEvent event) {
        calendarSyncService.syncAll(event.studentId());
    }
}
