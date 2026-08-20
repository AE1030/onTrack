package org.tracker.gpatracker.tenancy;

import java.util.function.Supplier;

/**
 * The single source of truth for "who am I acting as" for the duration of a request.
 *
 * <p>Bound once by {@code JwtFilter} from signed JWT claims — never from anything the client
 * controls — and cleared in a {@code finally} so a pooled Tomcat thread cannot carry tenant
 * state into the next request.
 *
 * <p>Deliberately a plain {@link ThreadLocal} and <b>not</b> an {@code InheritableThreadLocal}:
 * inheritable values are captured at thread <i>creation</i>, so a pooled worker would keep
 * whichever tenant happened to be current when the pool grew — forever, and invisibly.
 * Propagation to {@code @Async} work is explicit, via {@link TenantTaskDecorator}.
 *
 * <p>Two escape hatches exist for flows that run before a JWT is available
 * ({@link #callAs}) or that legitimately span all tenants ({@link #callAsSystem}). Both
 * save the previous value and restore it in a {@code finally}, so a nested scope can never
 * corrupt an outer one.
 */
public final class UserContext {

    /**
     * @param userId  the {@code Users} id — auth identity
     * @param ownerId the {@code Student} id — the tenant that owns domain rows
     * @param system  true inside {@link #callAsSystem}, where no single tenant applies
     */
    record Tenant(Long userId, Long ownerId, boolean system) {}

    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    // ---------------------------------------------------------------- binding

    /** Bind the current request's identity. Call exactly once per request, from the filter. */
    public static void bind(Long userId, Long ownerId) {
        CURRENT.set(new Tenant(userId, ownerId, false));
    }

    /** Unbind. MUST be called from a {@code finally} — see the class javadoc. */
    public static void clear() {
        CURRENT.remove();
    }

    // ---------------------------------------------------------------- reading

    /**
     * The tenant that owns rows written or read right now.
     *
     * @throws IllegalStateException if nothing is bound, or if called inside a system scope.
     *         Throwing rather than returning {@code null} is deliberate: a null would render the
     *         Hibernate filter condition as {@code student_id = null}, which matches nothing —
     *         silently correct for reads but silently wrong for anything that expected to see
     *         every tenant.
     */
    public static Long requireOwnerId() {
        Tenant t = CURRENT.get();
        if (t == null) {
            throw new IllegalStateException(
                    "No tenant bound to this thread. Authenticated requests bind one in JwtFilter; "
                            + "background work must wrap itself in UserContext.callAs(...) or callAsSystem(...).");
        }
        if (t.system()) {
            throw new IllegalStateException(
                    "Running in system scope, which spans all tenants and has no single owner. "
                            + "Wrap in UserContext.callAs(...) if you need to act as one student.");
        }
        if (t.ownerId() == null) {
            throw new IllegalStateException(
                    "Tenant is bound but has no student id. This usually means the account was never "
                            + "email-verified, since the Student row is created at verification time.");
        }
        return t.ownerId();
    }

    /** The owner id, or {@code null} if none is bound. For code that must not throw. */
    public static Long ownerIdOrNull() {
        Tenant t = CURRENT.get();
        return (t == null || t.system()) ? null : t.ownerId();
    }

    /** @throws IllegalStateException if no tenant is bound. */
    public static Long requireUserId() {
        Tenant t = CURRENT.get();
        if (t == null || t.userId() == null) {
            throw new IllegalStateException("No authenticated user bound to this thread.");
        }
        return t.userId();
    }

    /** The user id, or {@code null} if none is bound. */
    public static Long userIdOrNull() {
        Tenant t = CURRENT.get();
        return t == null ? null : t.userId();
    }

    /** True while inside {@link #callAsSystem}. */
    public static boolean isSystem() {
        Tenant t = CURRENT.get();
        return t != null && t.system();
    }

    /** True when any tenant or system scope is bound. */
    public static boolean isBound() {
        return CURRENT.get() != null;
    }

    // --------------------------------------------------------- escape hatch 1

    /**
     * Act as a specific tenant for the duration of {@code body}.
     *
     * <p>For flows that run before a JWT exists: email verification creating the initial
     * {@code Student} row, and the Google OAuth callback whose identity comes from a signed
     * {@code state} token rather than an {@code Authorization} header.
     */
    public static <T> T callAs(Long userId, Long ownerId, Supplier<T> body) {
        Tenant previous = CURRENT.get();
        CURRENT.set(new Tenant(userId, ownerId, false));
        try {
            return body.get();
        } finally {
            restore(previous);
        }
    }

    /** {@link #callAs} for work that returns nothing. */
    public static void runAs(Long userId, Long ownerId, Runnable body) {
        callAs(userId, ownerId, () -> {
            body.run();
            return null;
        });
    }

    // --------------------------------------------------------- escape hatch 2

    /**
     * Run {@code body} across all tenants.
     *
     * <p>For scheduled jobs that legitimately aggregate every student's data, and for writes to
     * global catalog tables. Callers that touch user-owned entities must <em>also</em> disable the
     * Hibernate filter on the session — see {@code TenantScope} — because this only marks the
     * scope; it does not change what the session filters.
     */
    public static <T> T callAsSystem(Supplier<T> body) {
        Tenant previous = CURRENT.get();
        CURRENT.set(new Tenant(null, null, true));
        try {
            return body.get();
        } finally {
            restore(previous);
        }
    }

    /** {@link #callAsSystem} for work that returns nothing. */
    public static void runAsSystem(Runnable body) {
        callAsSystem(() -> {
            body.run();
            return null;
        });
    }

    // ------------------------------------------------- for TenantTaskDecorator

    /** Snapshot the current binding so another thread can adopt it. */
    static Tenant capture() {
        return CURRENT.get();
    }

    /** Re-establish a snapshot taken by {@link #capture()}. A null snapshot clears. */
    static void restore(Tenant snapshot) {
        if (snapshot == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(snapshot);
        }
    }
}
