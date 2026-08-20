/**
 * Tenant isolation.
 *
 * <p>The filter declared here is the enforcement point. Two attributes carry the design:
 *
 * <ul>
 *   <li>{@code autoEnabled} — the filter turns itself on for every session, so nothing has to
 *       enable it per request. Doing that from a servlet filter would be silently broken:
 *       open-in-view binds the EntityManager in an MVC interceptor that runs <em>after</em> all
 *       servlet filters, so unwrapping a Session there gets a throwaway one and the call is a
 *       no-op with no error.
 *   <li>{@code applyToLoadByKey} — extends the filter to {@code em.find()},
 *       {@code getReference()} and Spring Data's {@code findById}. Without it the filter covers
 *       only queries and collection loads, and {@code findById} would happily return another
 *       tenant's row.
 * </ul>
 *
 * <p>The filter does <em>not</em> apply to native queries. There is one in the codebase
 * ({@code RolesRepo}), on a global table with an explicit id, so it is unaffected — but no new
 * native queries should be written against user-owned tables.
 */
@FilterDef(
        name = OwnerFilter.NAME,
        defaultCondition = OwnerFilter.CONDITION,
        parameters = @ParamDef(
                name = OwnerFilter.PARAM,
                type = Long.class,
                resolver = CurrentOwnerIdResolver.class),
        autoEnabled = true,
        applyToLoadByKey = true
)
package org.tracker.gpatracker.tenancy;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
