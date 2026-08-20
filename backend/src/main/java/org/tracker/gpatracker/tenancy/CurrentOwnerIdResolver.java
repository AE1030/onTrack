package org.tracker.gpatracker.tenancy;

import java.util.function.Supplier;

/**
 * Supplies the filter's owner parameter from {@link UserContext}.
 *
 * <p>Hibernate instantiates this itself, so it cannot be a Spring bean and must reach the context
 * statically.
 *
 * <p>It deliberately throws rather than returning null when no tenant is bound. A null would
 * render the condition as {@code student_id = null}, which matches nothing — correct-looking for
 * reads, but silently wrong for any job that expected to span tenants. Code that legitimately
 * spans tenants disables the filter via {@link TenantScope} instead.
 */
public class CurrentOwnerIdResolver implements Supplier<Long> {

    @Override
    public Long get() {
        return UserContext.requireOwnerId();
    }
}
