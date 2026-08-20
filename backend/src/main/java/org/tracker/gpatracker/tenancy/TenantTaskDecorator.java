package org.tracker.gpatracker.tenancy;

import org.springframework.core.task.TaskDecorator;

/**
 * Carries the submitting thread's tenant onto the worker that runs the task.
 *
 * <p>{@link UserContext} is a plain {@code ThreadLocal}, so {@code @Async} work would otherwise
 * run with nothing bound. That is not hypothetical here: the syllabus extraction job already
 * fails this way, and the exception is swallowed by a surrounding catch — so the follow-up
 * assessment-table refresh silently never runs.
 *
 * <p>The worker's previous binding is restored in a {@code finally} rather than cleared, so a
 * pooled thread is left exactly as it was found.
 */
public class TenantTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        UserContext.Tenant captured = UserContext.capture();
        return () -> {
            UserContext.Tenant previous = UserContext.capture();
            UserContext.restore(captured);
            try {
                task.run();
            } finally {
                UserContext.restore(previous);
            }
        };
    }
}
