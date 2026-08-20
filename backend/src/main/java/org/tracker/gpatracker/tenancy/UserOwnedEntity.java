package org.tracker.gpatracker.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;

/**
 * Base class for entities whose rows belong to exactly one tenant.
 *
 * <p>The column is physically {@code student_id}; the field is called {@code ownerId} so the
 * abstraction reads correctly regardless of which id space a given table happens to use.
 *
 * <p>{@code nullable = false} means a row cannot exist without an owner, and
 * {@code updatable = false} means an existing row cannot be repointed at a different one — the
 * two properties that make isolation structural rather than a convention callers must remember.
 *
 * <p><b>Subclasses must still carry their own {@code @Filter(name = OwnerFilter.NAME)}.</b>
 * Hibernate binds filters from the concrete {@code @Entity} class and does not inherit them from
 * a {@code @MappedSuperclass}. Omitting it produces no error and no warning — just an unfiltered
 * table. {@code TenantMappingTest} exists to catch exactly that.
 */
@MappedSuperclass
public abstract class UserOwnedEntity extends BaseEntity implements UserOwned {

    @Column(name = "student_id", nullable = false, updatable = false)
    private Long ownerId;

    /**
     * Stamps the owner from the current request so callers never have to set it — and, more to
     * the point, so they cannot set it to someone else. An unowned insert fails loudly here
     * rather than reaching the database.
     */
    @PrePersist
    void stampOwner() {
        if (ownerId == null) {
            ownerId = UserContext.requireOwnerId();
        }
    }

    @Override
    public Long getOwnerId() {
        return ownerId;
    }

    /**
     * Only useful before the first persist; the column is not updatable, so a change to an
     * already-persisted row is ignored by the database.
     */
    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }
}
