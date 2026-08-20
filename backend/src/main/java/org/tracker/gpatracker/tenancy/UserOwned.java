package org.tracker.gpatracker.tenancy;

/**
 * Marks a persistent type whose rows belong to exactly one tenant.
 *
 * <p>Most implementations inherit the owner column from {@link UserOwnedEntity}.
 * {@code CourseEnrollement} implements this directly instead: its {@code student_id} is already
 * half of its composite primary key, and mapping the same column twice is a boot failure — so it
 * reads the owner out of the key it already has.
 *
 * <p>Implementing this interface is <em>not</em> what enforces isolation. Each concrete
 * {@code @Entity} must also carry {@code @Filter}, because Hibernate binds filters from the entity
 * class and does not inherit them from a {@code @MappedSuperclass}. {@code TenantMappingTest}
 * asserts the two never drift apart.
 */
public interface UserOwned {

    /** The {@code Student} id that owns this row. */
    Long getOwnerId();
}
