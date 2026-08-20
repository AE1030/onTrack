package org.tracker.gpatracker.tenancy;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.repository.StudentRepo;
import org.tracker.gpatracker.courses.model.PastCourse;
import org.tracker.gpatracker.courses.repository.PastCourseRepository;

import java.util.List;

import org.tracker.gpatracker.support.ContainerIntegrationBase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The decisive test. Seeds two students and checks that, acting as one, the other's rows are
 * unreachable through every access path the application actually uses.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OwnerFilterIntegrationTest extends ContainerIntegrationBase {

    @Autowired
    private EntityManager em;

    @Autowired
    private StudentRepo studentRepo;

    @Autowired
    private PastCourseRepository pastCourseRepo;

    @Autowired
    private TenantScope tenantScope;

    private Long studentA;
    private Long studentB;
    private Long rowOfA;
    private Long rowOfB;

    @BeforeEach
    void seed() {
        studentA = UserContext.callAsSystem(() -> studentRepo.save(new Student()).getId());
        studentB = UserContext.callAsSystem(() -> studentRepo.save(new Student()).getId());

        rowOfA = UserContext.callAs(null, studentA, () -> savePastCourse("A-COURSE")).getId();
        rowOfB = UserContext.callAs(null, studentB, () -> savePastCourse("B-COURSE")).getId();

        em.flush();
        // Load-bearing: without this the seeded rows sit in the first-level cache and find()
        // returns them with no SELECT, so the isolation assertions would pass for the wrong reason.
        em.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private PastCourse savePastCourse(String name) {
        PastCourse course = new PastCourse();
        course.setName(name);
        course.setUnits("3");
        course.setGrade("A");
        return pastCourseRepo.save(course);
    }

    // ------------------------------------------------------------------ gate

    @Test
    @DisplayName("GATE: find() on another tenant's row returns null")
    void findByIdCannotCrossTenants() {
        UserContext.runAs(null, studentA, () -> {
            assertThat(em.find(PastCourse.class, rowOfB))
                    .as("if this is non-null, applyToLoadByKey is not in effect and findById leaks")
                    .isNull();
            assertThat(em.find(PastCourse.class, rowOfA))
                    .as("the tenant's own row must still be reachable")
                    .isNotNull();
        });
    }

    @Test
    @DisplayName("GATE: Spring Data findById cannot cross tenants")
    void springDataFindByIdCannotCrossTenants() {
        UserContext.runAs(null, studentA, () -> {
            assertThat(pastCourseRepo.findById(rowOfB)).isEmpty();
            assertThat(pastCourseRepo.findById(rowOfA)).isPresent();
        });
    }

    // ----------------------------------------------------------- query paths

    @Test
    @DisplayName("JPQL returns only the current tenant's rows")
    void jpqlIsFiltered() {
        UserContext.runAs(null, studentA, () -> {
            List<PastCourse> rows = em.createQuery("from PastCourse", PastCourse.class).getResultList();
            assertThat(rows).extracting(PastCourse::getOwnerId).containsOnly(studentA);
        });
    }

    @Test
    @DisplayName("findAll returns only the current tenant's rows")
    void findAllIsFiltered() {
        UserContext.runAs(null, studentA,
                () -> assertThat(pastCourseRepo.findAll()).extracting(PastCourse::getOwnerId).containsOnly(studentA));
    }

    @Test
    @DisplayName("a derived finder cannot be used to read another tenant")
    void derivedFinderCannotCrossTenants() {
        UserContext.runAs(null, studentA,
                () -> assertThat(pastCourseRepo.findByOwnerId(studentB)).isEmpty());
    }

    // ------------------------------------------------------------ fail-closed

    @Test
    @DisplayName("with no tenant bound, reads fail rather than returning everything")
    void unboundReadsFailClosed() {
        // Depending on the access path the resolver's exception may surface directly or wrapped
        // in a persistence exception, so assert on the whole chain rather than a fixed depth.
        assertThatThrownBy(() -> em.createQuery("from PastCourse", PastCourse.class).getResultList())
                .hasStackTraceContaining("No tenant bound");
    }

    @Test
    @DisplayName("with no tenant bound, an insert is rejected rather than written unowned")
    void unboundWritesFailClosed() {
        assertThatThrownBy(() -> {
            savePastCourse("ORPHAN");
            em.flush();
        }).hasStackTraceContaining("No tenant bound");
    }

    // ---------------------------------------------------------- system scope

    @Test
    @DisplayName("system scope sees every tenant, and the filter is restored afterwards")
    void systemScopeSeesAllAndRestores() {
        UserContext.runAs(null, studentA, () -> {
            List<PastCourse> all = tenantScope.unfiltered(
                    () -> em.createQuery("from PastCourse", PastCourse.class).getResultList());

            assertThat(all).extracting(PastCourse::getOwnerId)
                    .containsExactlyInAnyOrder(studentA, studentB);

            assertThat(em.createQuery("from PastCourse", PastCourse.class).getResultList())
                    .as("the filter must be re-enabled when the system scope exits")
                    .extracting(PastCourse::getOwnerId)
                    .containsOnly(studentA);
        });
    }

    // ------------------------------------------------------- first-level cache

    /**
     * Hazard 6, and the reason {@code UserScopedRepositoryImpl} exists.
     *
     * <p>{@code applyToLoadByKey} only helps when a {@code SELECT} is actually issued. A system-scope
     * block earlier in the same transaction pulls other tenants' rows into the persistence context,
     * and a later {@code findById} is then answered straight from that cache — no query, so no
     * filter. An {@code em.find} here would still hand back B's row; the repository override is what
     * turns it back into a miss.
     */
    @Test
    @DisplayName("a row cached by an earlier system-scope read is still not readable by another tenant")
    void cachedRowFromSystemScopeIsNotReadableCrossTenant() {
        UserContext.runAs(null, studentA, () -> {
            // Warm the L1 cache with every tenant's rows, as a cross-tenant job would.
            tenantScope.unfiltered(() -> em.createQuery("from PastCourse", PastCourse.class).getResultList());

            assertThat(em.contains(em.getReference(PastCourse.class, rowOfB)))
                    .as("this test is only meaningful if B's row really is cached")
                    .isTrue();

            assertThat(pastCourseRepo.findById(rowOfB))
                    .as("the cache must not become a way around the filter")
                    .isEmpty();
            assertThat(pastCourseRepo.findById(rowOfA))
                    .as("the tenant's own cached row must still be returned")
                    .isPresent();
        });
    }

    @Test
    @DisplayName("system scope can still read a cached row belonging to anyone")
    void systemScopeReadsCachedRowsOfAnyTenant() {
        UserContext.runAs(null, studentA, () -> tenantScope.unfiltered(() -> {
            assertThat(pastCourseRepo.findById(rowOfB))
                    .as("the ownership assert must not break legitimate cross-tenant jobs")
                    .isPresent();
            return null;
        }));
    }

    // ---------------------------------------------------------------- stamping

    @Test
    @DisplayName("the owner is stamped automatically on insert")
    void ownerIsStampedFromContext() {
        UserContext.runAs(null, studentB, () -> {
            PastCourse saved = savePastCourse("STAMPED");
            em.flush();
            assertThat(saved.getOwnerId()).isEqualTo(studentB);
        });
    }

    @Test
    @DisplayName("the owner cannot be repointed after insert")
    void ownerIsImmutable() {
        UserContext.runAs(null, studentA, () -> {
            PastCourse mine = em.find(PastCourse.class, rowOfA);
            mine.setOwnerId(studentB);
            em.flush();
            em.clear();
        });

        // Read back under system scope: if the hijack had succeeded the row would now belong to B,
        // and reading it as A would simply return nothing — which looks identical to success.
        tenantScope.unfiltered(() -> {
            PastCourse reloaded = em.find(PastCourse.class, rowOfA);
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.getOwnerId())
                    .as("updatable=false must keep the row with its original owner")
                    .isEqualTo(studentA);
            return null;
        });
    }
}
