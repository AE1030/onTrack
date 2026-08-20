package org.tracker.gpatracker.tenancy.support;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.tracker.gpatracker.tenancy.UserContext;

import java.util.concurrent.CompletableFuture;

/**
 * Reports the tenant an {@code @Async} worker actually observed, and on which thread.
 *
 * <p>A real bean rather than a lambda because {@code @Async} is applied by a proxy — invoking a
 * lambda directly would run inline on the caller's thread and prove nothing.
 */
@Component
public class AsyncProbe {

    /** What one worker saw. {@code threadName} is what makes pool reuse visible. */
    public record Observation(Long ownerId, boolean bound, String threadName) {
    }

    private static Observation here() {
        return new Observation(
                UserContext.ownerIdOrNull(),
                UserContext.isBound(),
                Thread.currentThread().getName());
    }

    @Async
    public CompletableFuture<Observation> observe() {
        return CompletableFuture.completedFuture(here());
    }

    /**
     * Sleeps before reporting, so a caller can hold every core thread busy at once and force the
     * pool to hand later tasks to threads that already ran an earlier tenant's task. Without that
     * overlap the executor may keep reusing a single thread and the reuse case never arises.
     */
    @Async
    public CompletableFuture<Observation> observeAfter(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return CompletableFuture.completedFuture(here());
    }
}
