package org.tracker.gpatracker.tenancy;

import org.springframework.stereotype.Component;

/**
 * Exposes {@link UserContext} to SpEL inside Spring Data Mongo {@code @Query} annotations.
 *
 * <p>SpEL can only reference beans, and {@code UserContext} is deliberately static. This is the
 * bridge, and nothing else should use it — Java callers should go to {@code UserContext} directly.
 *
 * <p>Referenced as {@code ?#{@userContextAccessor.ownerId()}}, which is evaluated <em>before</em>
 * the query is sent, so the tenant criterion is applied by MongoDB rather than after loading.
 */
@Component("userContextAccessor")
public class UserContextAccessor {

    /** @throws IllegalStateException if no tenant is bound — queries fail closed, not open. */
    public Long ownerId() {
        return UserContext.requireOwnerId();
    }
}
