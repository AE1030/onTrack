package org.tracker.gpatracker.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Runs work across all tenants.
 *
 * <p>{@link UserContext#callAsSystem} alone is not enough. The filter is {@code autoEnabled}, so
 * it is on for every session; leaving it on with no owner bound would make the resolver throw.
 * This turns it off on the current session for the duration of the body, and restores it in a
 * {@code finally}.
 *
 * <p>Must be called inside an active transaction — it operates on the session bound to the
 * current persistence context.
 *
 * <p>The leaderboard ranking job is the motivating case: it reads every student's frozen season
 * baseline and rewrites the board from them. Under an enabled filter with no tenant, it would
 * silently process nothing.
 */
@Component
public class TenantScope {

    @PersistenceContext
    private EntityManager entityManager;

    /** Run {@code body} with the owner filter disabled and the context marked as system. */
    public <T> T unfiltered(Supplier<T> body) {
        Session session = entityManager.unwrap(Session.class);
        boolean wasEnabled = session.getEnabledFilter(OwnerFilter.NAME) != null;
        if (wasEnabled) {
            session.disableFilter(OwnerFilter.NAME);
        }
        try {
            return UserContext.callAsSystem(body);
        } finally {
            if (wasEnabled) {
                session.enableFilter(OwnerFilter.NAME);
            }
        }
    }

    /** {@link #unfiltered(Supplier)} for work that returns nothing. */
    public void unfiltered(Runnable body) {
        unfiltered(() -> {
            body.run();
            return null;
        });
    }
}
