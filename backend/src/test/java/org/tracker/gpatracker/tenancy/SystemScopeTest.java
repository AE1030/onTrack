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
import org.tracker.gpatracker.courses.model.DueDateConsensus;
import org.tracker.gpatracker.courses.model.DueDateOverride;
import org.tracker.gpatracker.courses.repository.DueDateConsensusRepository;
import org.tracker.gpatracker.courses.repository.DueDateOverrideRepository;
import org.tracker.gpatracker.courses.service.DueDateConsensusService;

import java.time.LocalDate;
import java.util.Optional;

import org.tracker.gpatracker.support.ContainerIntegrationBase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the highest-severity hazard in the whole refactor.
 *
 * <p>{@code DueDateConsensusService} is genuinely cross-tenant: it aggregates every student's
 * proposed dates, and it runs on a scheduler thread where nothing has bound a tenant. Under an
 * auto-enabled owner filter that is a trap in two directions. If the parameter resolver returned
 * null, the condition would become {@code student_id = null}, the {@code findAll} would see zero
 * rows, and the {@code votes < 2} branch would then <em>delete</em> the consensus rows it should
 * have been maintaining — silent data loss on a cron. Because the resolver throws instead, the
 * failure would at least be loud; but the job still has to work, which is what the system-scope
 * wrapper is for.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SystemScopeTest extends ContainerIntegrationBase {

    private static final String COURSE = "SFWRENG 3XX";
    private static final String ASSESSMENT = "Midterm";
    private static final LocalDate PROPOSED = LocalDate.of(2026, 3, 1);

    @Autowired
    private DueDateConsensusService consensusService;

    @Autowired
    private DueDateConsensusRepository consensusRepo;

    @Autowired
    private DueDateOverrideRepository overrideRepo;

    @Autowired
    private StudentRepo studentRepo;

    @Autowired
    private EntityManager em;

    private Long studentA;
    private Long studentB;

    @BeforeEach
    void seed() {
        studentA = UserContext.callAsSystem(() -> studentRepo.save(new Student()).getId());
        studentB = UserContext.callAsSystem(() -> studentRepo.save(new Student()).getId());

        UserContext.runAs(null, studentA, () -> saveOverride(PROPOSED));
        UserContext.runAs(null, studentB, () -> saveOverride(PROPOSED));

        em.flush();
        em.clear();
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void saveOverride(LocalDate date) {
        DueDateOverride override = new DueDateOverride();
        override.setCourseCode(COURSE);
        override.setAssessmentName(ASSESSMENT);
        override.setProposedDueDate(date);
        overrideRepo.save(override);
    }

    /**
     * The scheduler binds nothing, so this runs exactly as the cron does.
     */
    @Test
    @DisplayName("the consensus job still sees every tenant's overrides with no tenant bound")
    void consensusJobRunsUnfilteredOnAnUnboundThread() {
        assertThat(UserContext.isBound())
                .as("this test is only meaningful on an unbound thread, like the scheduler's")
                .isFalse();

        consensusService.setConsensusDueDate();
        em.flush();

        Optional<DueDateConsensus> consensus =
                consensusRepo.findByCourseCodeAndAssessmentName(COURSE, ASSESSMENT);

        assertThat(consensus).as("both students voted, so a consensus row must exist").isPresent();
        assertThat(consensus.get().getVotes())
                .as("seeing only one student's vote means the filter was still applied")
                .isEqualTo(2L);
        assertThat(consensus.get().getDueDate()).isEqualTo(PROPOSED);
    }

    /**
     * The counterpart: the same aggregation without the system-scope wrapper must fail, not quietly
     * return a partial answer. If this ever stops throwing, the filter has stopped being
     * auto-enabled and {@link #consensusJobRunsUnfilteredOnAnUnboundThread} passes for free.
     */
    @Test
    @DisplayName("the same unscoped read fails closed rather than returning a partial view")
    void withoutSystemScopeTheReadFails() {
        assertThatThrownBy(() -> overrideRepo.findAll())
                .hasStackTraceContaining("No tenant bound");
    }

    @Test
    @DisplayName("system scope is exited even when the job throws")
    void systemScopeIsRestoredOnFailure() {
        assertThatThrownBy(() -> UserContext.callAsSystem(() -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        assertThat(UserContext.isSystem()).isFalse();
        assertThat(UserContext.isBound()).isFalse();
    }

    @Test
    @DisplayName("a tenant scope surrounding the job is left intact")
    void outerTenantScopeSurvives() {
        UserContext.runAs(null, studentA, () -> {
            consensusService.setConsensusDueDate();

            assertThat(UserContext.requireOwnerId())
                    .as("the job must not leave the caller acting as someone else")
                    .isEqualTo(studentA);
            assertThat(UserContext.isSystem()).isFalse();
            assertThat(overrideRepo.findAll())
                    .as("the filter must be back on for the caller")
                    .extracting(DueDateOverride::getOwnerId)
                    .containsOnly(studentA);
        });
    }
}
