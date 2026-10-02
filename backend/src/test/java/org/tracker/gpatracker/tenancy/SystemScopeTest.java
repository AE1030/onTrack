package org.tracker.gpatracker.tenancy;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.repository.StudentRepo;
import org.tracker.gpatracker.leaderboard.model.EntryStatus;
import org.tracker.gpatracker.leaderboard.model.LeaderboardEntry;
import org.tracker.gpatracker.leaderboard.model.ScoreMode;
import org.tracker.gpatracker.leaderboard.model.SeasonBaseline;
import org.tracker.gpatracker.leaderboard.repository.LeaderboardEntryRepository;
import org.tracker.gpatracker.leaderboard.repository.SeasonBaselineRepository;
import org.tracker.gpatracker.leaderboard.service.LeaderboardRankingService;
import org.tracker.gpatracker.leaderboard.service.LeaderboardRankingService.RecomputeResult;
import org.tracker.gpatracker.leaderboard.service.ProjectedGpaService;

import java.math.BigDecimal;

import org.tracker.gpatracker.support.ContainerIntegrationBase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Pins the highest-severity hazard in the whole refactor.
 *
 * <p>{@code LeaderboardRankingService} is genuinely cross-tenant: it reads every student's frozen
 * baseline, and it runs on a scheduler thread or an unauthenticated job trigger where nothing has
 * bound a tenant. Under an auto-enabled owner filter that is a trap in two directions. If the
 * parameter resolver returned null, the condition would become {@code student_id = null}, the
 * {@code findBySeason} would see zero rows, and the job would rewrite nobody's score while
 * reporting success. Because the resolver throws instead, the failure would at least be loud; but
 * the job still has to work, which is what the system-scope wrapper is for.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SystemScopeTest extends ContainerIntegrationBase {

    private static final String SEASON = "Winter 2026";

    /**
     * Mocked so the job needs no assessment tables: every student projects to their 10.00 target,
     * and the unstubbed term tables come back as Mockito's empty list, so no behaviour flags fire.
     */
    @MockitoBean
    private ProjectedGpaService projectedGpa;

    @Autowired
    private LeaderboardRankingService rankingService;

    @Autowired
    private SeasonBaselineRepository baselines;

    @Autowired
    private LeaderboardEntryRepository entries;

    @Autowired
    private StudentRepo studentRepo;

    @Autowired
    private EntityManager em;

    private Long studentA;
    private Long studentB;

    @BeforeEach
    void seed() {
        when(projectedGpa.projectedGpa12(anyLong(), anyString(), any())).thenReturn(new BigDecimal("10.00"));

        studentA = UserContext.callAsSystem(() -> studentRepo.save(new Student()).getId());
        studentB = UserContext.callAsSystem(() -> studentRepo.save(new Student()).getId());

        seedEntrant(studentA, "alpha");
        seedEntrant(studentB, "bravo");

        em.flush();
        em.clear();
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void seedEntrant(Long studentId, String handle) {
        UserContext.runAs(null, studentId, () -> {
            SeasonBaseline baseline = new SeasonBaseline();
            baseline.setSeason(SEASON);
            baseline.setBaselineGpa12(new BigDecimal("8.00"));
            baseline.setTargetGpa12(new BigDecimal("10.00"));
            baseline.setScoreMode(ScoreMode.GROWTH);
            baselines.save(baseline);

            LeaderboardEntry entry = new LeaderboardEntry();
            entry.setStudentId(studentId);
            entry.setSeason(SEASON);
            entry.setHandle(handle);
            entry.setStatus(EntryStatus.ACTIVE);
            entry.setScoreMode(ScoreMode.GROWTH);
            entry.setScore(BigDecimal.ZERO);
            entry.setAmbition(BigDecimal.ZERO);
            entry.setBehaviorScore(BigDecimal.ONE);
            entry.setBehaviorFlags(0);
            entries.save(entry);
        });
    }

    /**
     * Nothing is bound, so this runs exactly as the cron and the job trigger do.
     */
    @Test
    @DisplayName("the ranking job still sees every tenant's baseline with no tenant bound")
    void rankingJobRunsUnfilteredOnAnUnboundThread() {
        assertThat(UserContext.isBound())
                .as("this test is only meaningful on an unbound thread, like the scheduler's")
                .isFalse();

        RecomputeResult result = rankingService.recompute();
        em.flush();
        em.clear();

        assertThat(result.total())
                .as("seeing only one student's baseline means the filter was still applied")
                .isEqualTo(2);
        assertThat(result.rescored()).isEqualTo(2);
        assertThat(entries.findBySeasonAndStatusOrderByScoreDescHandleAsc(SEASON, EntryStatus.ACTIVE))
                .as("a delivered 8.00 to 10.00 goal with no behaviour flags")
                .extracting(LeaderboardEntry::getScore)
                .allSatisfy(score -> assertThat(score).isEqualByComparingTo("11125.00"));
    }

    /**
     * The counterpart: the same read without the system-scope wrapper must fail, not quietly
     * return a partial answer. If this ever stops throwing, the filter has stopped being
     * auto-enabled and {@link #rankingJobRunsUnfilteredOnAnUnboundThread} passes for free.
     */
    @Test
    @DisplayName("the same unscoped read fails closed rather than returning a partial view")
    void withoutSystemScopeTheReadFails() {
        assertThatThrownBy(() -> baselines.findBySeason(SEASON))
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
            rankingService.recompute();

            assertThat(UserContext.requireOwnerId())
                    .as("the job must not leave the caller acting as someone else")
                    .isEqualTo(studentA);
            assertThat(UserContext.isSystem()).isFalse();
            assertThat(baselines.findAll())
                    .as("the filter must be back on for the caller")
                    .extracting(SeasonBaseline::getOwnerId)
                    .containsOnly(studentA);
        });
    }
}
