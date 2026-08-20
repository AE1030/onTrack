package org.tracker.gpatracker.tenancy;

import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;

import java.util.Optional;

/**
 * Repository base class that closes the one isolation gap the Hibernate filter cannot see.
 *
 * <p>{@code applyToLoadByKey = true} makes the filter apply to {@code find()}, but only when
 * {@code find()} actually issues a {@code SELECT}. If the entity is already in the persistence
 * context's first-level cache, Hibernate returns it directly and no SQL — and therefore no filter —
 * is involved. That is reachable in practice: a {@code callAsSystem} block loads rows across
 * tenants into the session, and a later {@code findById} in the same transaction gets one back
 * without a query.
 *
 * <p>Installed for <em>every</em> JPA repository via {@code repositoryBaseClass}, not just those
 * extending {@link UserScopedRepository}. The check is a no-op for entities that are not owned, and
 * covering everything means a repository that forgets to extend the marker interface is still safe;
 * the marker's job is documentation and enumeration, not enforcement.
 */
public class UserScopedRepositoryImpl<T, ID> extends SimpleJpaRepository<T, ID> {

    public UserScopedRepositoryImpl(JpaEntityInformation<T, ?> entityInformation, EntityManager em) {
        super(entityInformation, em);
    }

    /**
     * Returns empty rather than throwing on a cross-tenant hit, matching what the filter itself does
     * when the {@code SELECT} is issued. A caller should not be able to tell whether the row it was
     * denied is absent or merely someone else's.
     */
    @Override
    public Optional<T> findById(ID id) {
        return super.findById(id).filter(UserScopedRepositoryImpl::visibleToCurrentTenant);
    }

    /**
     * {@code getReferenceById} deliberately keeps the inherited behaviour. It hands back an
     * uninitialised proxy, and checking its owner here would force a load on every call — turning a
     * cheap association stand-in into a query. The proxy still resolves through the filter when it
     * is first touched.
     */

    static boolean visibleToCurrentTenant(Object entity) {
        if (!(entity instanceof UserOwned owned)) {
            return true;
        }
        if (UserContext.isSystem()) {
            return true;
        }
        // Throws when nothing is bound, exactly as the filter's parameter resolver does, so a
        // forgotten scope fails closed instead of quietly returning the row.
        return UserContext.requireOwnerId().equals(owned.getOwnerId());
    }
}
