package org.tracker.gpatracker.tenancy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ThreadLocal contract. No Spring — this is pure state management, and the failure modes
 * it guards (a scope corrupting its caller, a tenant leaking onto a pooled thread) are exactly
 * the ones that are invisible in higher-level tests.
 */
class UserContextTest {

    private static final Long USER_A = 1L;
    private static final Long STUDENT_A = 100L;
    private static final Long USER_B = 2L;
    private static final Long STUDENT_B = 200L;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---------------------------------------------------------------- binding

    @Test
    @DisplayName("bind then read returns what was bound")
    void bindThenRead() {
        UserContext.bind(USER_A, STUDENT_A);

        assertThat(UserContext.requireOwnerId()).isEqualTo(STUDENT_A);
        assertThat(UserContext.requireUserId()).isEqualTo(USER_A);
        assertThat(UserContext.isBound()).isTrue();
        assertThat(UserContext.isSystem()).isFalse();
    }

    @Test
    @DisplayName("nothing bound fails loudly rather than returning null")
    void unboundThrows() {
        assertThatThrownBy(UserContext::requireOwnerId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant bound");

        assertThat(UserContext.ownerIdOrNull()).isNull();
        assertThat(UserContext.isBound()).isFalse();
    }

    @Test
    @DisplayName("clear unbinds")
    void clearUnbinds() {
        UserContext.bind(USER_A, STUDENT_A);
        UserContext.clear();

        assertThat(UserContext.isBound()).isFalse();
        assertThatThrownBy(UserContext::requireOwnerId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a bound user with no student id is reported distinctly")
    void boundWithoutStudentId() {
        UserContext.bind(USER_A, null);

        assertThat(UserContext.requireUserId()).isEqualTo(USER_A);
        assertThatThrownBy(UserContext::requireOwnerId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no student id");
    }

    // ------------------------------------------------------ callAs / restore

    @Test
    @DisplayName("callAs returns the body's value and restores the previous tenant")
    void callAsRestoresPrevious() {
        UserContext.bind(USER_A, STUDENT_A);

        String result = UserContext.callAs(USER_B, STUDENT_B, () -> {
            assertThat(UserContext.requireOwnerId()).isEqualTo(STUDENT_B);
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(UserContext.requireOwnerId()).isEqualTo(STUDENT_A);
    }

    @Test
    @DisplayName("callAs restores even when the body throws")
    void callAsRestoresOnThrow() {
        UserContext.bind(USER_A, STUDENT_A);

        assertThatThrownBy(() -> UserContext.callAs(USER_B, STUDENT_B, () -> {
            throw new RuntimeException("boom");
        })).hasMessage("boom");

        assertThat(UserContext.requireOwnerId())
                .as("an exception inside the scope must not strand the outer tenant")
                .isEqualTo(STUDENT_A);
    }

    @Test
    @DisplayName("callAs from an unbound thread leaves it unbound afterwards")
    void callAsFromUnboundLeavesUnbound() {
        UserContext.callAs(USER_A, STUDENT_A, () -> UserContext.requireOwnerId());

        assertThat(UserContext.isBound())
                .as("restoring a null snapshot must clear, not bind null")
                .isFalse();
    }

    @Test
    @DisplayName("nested callAs restores the outer scope, not the outermost")
    void nestedCallAsRestoresImmediateOuter() {
        UserContext.bind(USER_A, STUDENT_A);

        UserContext.runAs(USER_B, STUDENT_B, () -> {
            UserContext.runAs(999L, 999L, () ->
                    assertThat(UserContext.requireOwnerId()).isEqualTo(999L));

            assertThat(UserContext.requireOwnerId())
                    .as("inner scope must not corrupt its caller")
                    .isEqualTo(STUDENT_B);
        });

        assertThat(UserContext.requireOwnerId()).isEqualTo(STUDENT_A);
    }

    // --------------------------------------------------------- system scope

    @Test
    @DisplayName("system scope has no owner and restores afterwards")
    void systemScopeRestores() {
        UserContext.bind(USER_A, STUDENT_A);

        UserContext.runAsSystem(() -> {
            assertThat(UserContext.isSystem()).isTrue();
            assertThatThrownBy(UserContext::requireOwnerId)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("system scope");
            assertThat(UserContext.ownerIdOrNull()).isNull();
        });

        assertThat(UserContext.isSystem()).isFalse();
        assertThat(UserContext.requireOwnerId()).isEqualTo(STUDENT_A);
    }

    @Test
    @DisplayName("callAs inside system scope re-establishes a concrete tenant")
    void callAsInsideSystemScope() {
        UserContext.runAsSystem(() -> {
            UserContext.runAs(USER_A, STUDENT_A, () -> {
                assertThat(UserContext.isSystem()).isFalse();
                assertThat(UserContext.requireOwnerId()).isEqualTo(STUDENT_A);
            });
            assertThat(UserContext.isSystem()).isTrue();
        });
    }

    // -------------------------------------------------------- thread isolation

    @Test
    @DisplayName("a thread created inside a bound scope inherits nothing")
    void freshThreadInheritsNothing() throws Exception {
        UserContext.bind(USER_A, STUDENT_A);

        AtomicReference<Long> seen = new AtomicReference<>(-1L);
        AtomicBoolean bound = new AtomicBoolean(true);

        // Created *while* a tenant is bound — an InheritableThreadLocal would leak here.
        Thread child = new Thread(() -> {
            bound.set(UserContext.isBound());
            seen.set(UserContext.ownerIdOrNull());
        });
        child.start();
        child.join(5_000);

        assertThat(bound)
                .as("UserContext must be a plain ThreadLocal, never InheritableThreadLocal")
                .isFalse();
        assertThat(seen.get()).isNull();
        assertThat(UserContext.requireOwnerId()).isEqualTo(STUDENT_A);
    }

    @Test
    @DisplayName("a pooled thread does not carry a tenant between tasks")
    void pooledThreadDoesNotCarryTenant() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Bind on the worker, then leave without clearing — the leak we are guarding against.
            pool.submit(() -> UserContext.bind(USER_A, STUDENT_A)).get(5, TimeUnit.SECONDS);

            Long leaked = pool.submit(UserContext::ownerIdOrNull).get(5, TimeUnit.SECONDS);
            assertThat(leaked)
                    .as("this is precisely why JwtFilter must clear() in a finally")
                    .isEqualTo(STUDENT_A);

            // And the decorator's restore-in-finally is what prevents it.
            Runnable decorated = new TenantTaskDecorator().decorate(() -> {
            });
            pool.submit(decorated).get(5, TimeUnit.SECONDS);
            assertThat(pool.submit(UserContext::ownerIdOrNull).get(5, TimeUnit.SECONDS))
                    .as("decorator must restore the worker's prior state")
                    .isEqualTo(STUDENT_A);

            pool.submit(UserContext::clear).get(5, TimeUnit.SECONDS);
            assertThat(pool.submit(UserContext::ownerIdOrNull).get(5, TimeUnit.SECONDS)).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------- decorator

    @Test
    @DisplayName("decorator carries the submitter's tenant onto the worker")
    void decoratorPropagatesTenant() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            UserContext.bind(USER_A, STUDENT_A);

            AtomicReference<Long> seen = new AtomicReference<>();
            Runnable decorated = new TenantTaskDecorator()
                    .decorate(() -> seen.set(UserContext.ownerIdOrNull()));

            pool.submit(decorated).get(5, TimeUnit.SECONDS);

            assertThat(seen.get()).isEqualTo(STUDENT_A);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("decorator submitted with nothing bound leaves the worker unbound")
    void decoratorWithNoTenantBindsNothing() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicBoolean bound = new AtomicBoolean(true);
            Runnable decorated = new TenantTaskDecorator()
                    .decorate(() -> bound.set(UserContext.isBound()));

            pool.submit(decorated).get(5, TimeUnit.SECONDS);

            assertThat(bound).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("decorator restores the worker's state even when the task throws")
    void decoratorRestoresOnThrow() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            UserContext.bind(USER_A, STUDENT_A);
            Runnable decorated = new TenantTaskDecorator().decorate(() -> {
                throw new RuntimeException("boom");
            });

            pool.submit(decorated);
            // The task threw; the worker must still come back clean for the next task.
            assertThat(pool.submit(UserContext::ownerIdOrNull).get(5, TimeUnit.SECONDS)).isNull();

            assertThatCode(() -> assertThat(UserContext.requireOwnerId()).isEqualTo(STUDENT_A))
                    .doesNotThrowAnyException();
        } finally {
            pool.shutdownNow();
        }
    }
}
