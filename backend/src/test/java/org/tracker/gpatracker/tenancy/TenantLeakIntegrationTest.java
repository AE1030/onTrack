package org.tracker.gpatracker.tenancy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.repository.StudentRepo;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.repository.UserRepo;
import org.tracker.gpatracker.security.service.JWTService;
import org.tracker.gpatracker.tenancy.support.AsyncProbe;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.tracker.gpatracker.support.ContainerIntegrationBase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The two leaks that ordinary tests never catch, because in both the wrong answer is still a
 * plausible one — the request succeeds, the async task runs, and nothing looks broken.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantLeakIntegrationTest extends ContainerIntegrationBase {

    /** Comfortably more tasks than core threads, so the pool must reuse workers. */
    private static final int ASYNC_TASKS = 24;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JWTService jwtService;

    @Autowired
    private UserRepo userRepo;

    @Autowired
    private StudentRepo studentRepo;

    @Autowired
    private AsyncProbe asyncProbe;

    private String tokenForA;
    private Long studentIdA;

    @BeforeEach
    void seed() {
        UserContext.clear();
        SecurityContextHolder.clearContext();

        // Not @Transactional: MockMvc's filter chain and the async pool both need rows that are
        // actually committed, so nothing here is rolled back between methods. A fresh identity per
        // test keeps the unique constraint on email satisfied.
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        UserContext.runAsSystem(() -> {
            Users user = new Users();
            user.setEmail("leak-" + suffix + "@example.com");
            user.setUsername("leak-" + suffix);
            user.setPassword("{noop}irrelevant");
            user.setEmailVerified(true);
            user.setLastLoginAt(LocalDateTime.now());
            Users savedUser = userRepo.save(user);

            Student student = new Student();
            student.setUser(savedUser);
            studentIdA = studentRepo.save(student).getId();

            tokenForA = jwtService.generateToken(savedUser.getEmail(), savedUser.getId(), studentIdA);
        });
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------ request leak

    /**
     * Hazard 5. MockMvc runs the filter chain on the calling thread, which is exactly the situation
     * a pooled Tomcat thread is in: if {@code JwtFilter} ever loses its {@code finally { clear() }},
     * the tenant is still bound here after the response has been written, and the next request this
     * thread serves inherits it.
     */
    @Test
    @DisplayName("an authenticated request leaves no tenant behind on its thread")
    void requestDoesNotLeakTenantOntoItsThread() throws Exception {
        mockMvc.perform(get("/api/my-courses/past-courses")
                .header("Authorization", "Bearer " + tokenForA));

        assertThat(UserContext.isBound())
                .as("JwtFilter must clear the tenant in a finally, or the next request inherits it")
                .isFalse();
    }

    @Test
    @DisplayName("a later unauthenticated request on the same thread sees no tenant")
    void unauthenticatedRequestAfterAnAuthenticatedOneSeesNothing() throws Exception {
        mockMvc.perform(get("/api/my-courses/past-courses")
                .header("Authorization", "Bearer " + tokenForA));
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/my-courses/past-courses"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("the tenancy work must not change authentication behaviour")
                        .isEqualTo(401));

        assertThat(UserContext.isBound()).isFalse();
    }

    // --------------------------------------------------------------- pool leak

    /**
     * Hazard 4. With an {@code InheritableThreadLocal} a worker captures whatever tenant was current
     * when the pool created it and keeps it forever, so a single-tenant test passes and production
     * quietly serves the wrong data. Enough tasks to guarantee reuse is what makes that visible.
     */
    @Test
    @DisplayName("each async worker sees the tenant of the task it was given, not a stale one")
    void asyncWorkersDoNotInheritOrRetainTenants() {
        List<CompletableFuture<AsyncProbe.Observation>> futures = new ArrayList<>();
        List<Long> submitted = new ArrayList<>();

        for (int i = 0; i < ASYNC_TASKS; i++) {
            Long tenant = 1000L + i;
            submitted.add(tenant);
            futures.add(UserContext.callAs(null, tenant, () -> asyncProbe.observeAfter(50)));
        }

        List<AsyncProbe.Observation> observations = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        List<Long> observed = observations.stream().map(AsyncProbe.Observation::ownerId).toList();
        assertThat(observed)
                .as("every task must observe its own submitter's tenant")
                .containsExactlyElementsOf(submitted);

        assertThat(observations.stream().map(AsyncProbe.Observation::threadName).distinct().count())
                .as("if every task got a fresh thread, reuse was never exercised and this proves nothing")
                .isLessThan(ASYNC_TASKS);
    }

    @Test
    @DisplayName("a worker reused after a bound task sees nothing when the submitter had no tenant")
    void asyncWorkerIsUnboundWhenSubmitterWas() {
        // Dirty the pool first, so the threads that serve the unbound tasks have already carried a
        // tenant. A decorator that restores instead of clearing would pass without this.
        List<CompletableFuture<AsyncProbe.Observation>> warmup = new ArrayList<>();
        for (int i = 0; i < ASYNC_TASKS; i++) {
            warmup.add(UserContext.callAs(null, 2000L + i, () -> asyncProbe.observeAfter(50)));
        }
        Map<String, Long> tenantByThread = warmup.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toMap(AsyncProbe.Observation::threadName,
                        AsyncProbe.Observation::ownerId,
                        (first, second) -> second));

        assertThat(UserContext.isBound()).isFalse();

        List<AsyncProbe.Observation> clean = new ArrayList<>();
        for (int i = 0; i < ASYNC_TASKS; i++) {
            clean.add(asyncProbe.observeAfter(50).join());
        }

        assertThat(clean).allSatisfy(observation -> {
            assertThat(observation.bound())
                    .as("worker %s still had a tenant bound", observation.threadName())
                    .isFalse();
            assertThat(observation.ownerId()).isNull();
        });

        assertThat(clean.stream().map(AsyncProbe.Observation::threadName))
                .as("the clean tasks must have landed on threads that previously carried a tenant")
                .anyMatch(tenantByThread::containsKey);
    }

    @Test
    @DisplayName("submitting an async task does not disturb the submitter's own tenant")
    void submitterKeepsItsTenant() {
        UserContext.runAs(null, studentIdA, () -> {
            asyncProbe.observe().join();
            assertThat(UserContext.requireOwnerId()).isEqualTo(studentIdA);
        });
    }

    /** Not strictly a leak, but it is what the decorator has to preserve to be worth having. */
    @Test
    @DisplayName("the decorator carries the tenant rather than dropping it")
    void tenantIsPropagatedAtAll() {
        AsyncProbe.Observation observation =
                UserContext.callAs(null, studentIdA, () -> asyncProbe.observe()).join();

        assertThat(observation.ownerId())
                .as("without propagation every @Async method that resolves the student throws")
                .isEqualTo(studentIdA);
    }
}
