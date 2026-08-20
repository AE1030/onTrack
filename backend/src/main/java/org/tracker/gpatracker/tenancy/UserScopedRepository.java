package org.tracker.gpatracker.tenancy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Declares that a table participates in the owner-filter contract.
 *
 * <p>Extending this is a statement of intent rather than the thing that enforces isolation — the
 * enforcement is the {@code @Filter} on the entity. What it adds is:
 *
 * <ul>
 *   <li>the first-level-cache assert in {@code UserScopedRepositoryImpl}, which covers the one path
 *       the Hibernate filter cannot see;</li>
 *   <li>something for {@code TenantMappingTest} to enumerate, so a repository over an owned entity
 *       that forgets to extend this fails in CI.</li>
 * </ul>
 *
 * <p>Note that Hibernate filters do <em>not</em> apply to native queries. No {@code nativeQuery}
 * method may be declared on a repository extending this; {@code TenantMappingTest} enforces that.
 *
 * @param <T> an owned entity — the bound is what makes the ownership assert type-safe
 */
@NoRepositoryBean
public interface UserScopedRepository<T extends UserOwned, ID> extends JpaRepository<T, ID> {
}
