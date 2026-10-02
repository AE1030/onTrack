package org.tracker.gpatracker.leaderboard;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.tracker.gpatracker.accounts.model.Student;
import org.tracker.gpatracker.accounts.repository.StudentRepo;
import org.tracker.gpatracker.leaderboard.model.EntryStatus;
import org.tracker.gpatracker.leaderboard.model.LeaderboardEntry;
import org.tracker.gpatracker.leaderboard.model.LeaderboardProfile;
import org.tracker.gpatracker.leaderboard.model.ScoreMode;
import org.tracker.gpatracker.leaderboard.model.SeasonBaseline;
import org.tracker.gpatracker.leaderboard.model.TranscriptUpload;
import org.tracker.gpatracker.leaderboard.repository.LeaderboardEntryRepository;
import org.tracker.gpatracker.leaderboard.repository.LeaderboardProfileRepository;
import org.tracker.gpatracker.leaderboard.repository.SeasonBaselineRepository;
import org.tracker.gpatracker.leaderboard.repository.TranscriptUploadRepository;
import org.tracker.gpatracker.support.ContainerIntegrationBase;
import org.tracker.gpatracker.tenancy.TenantScope;
import org.tracker.gpatracker.tenancy.UserContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The half of the leaderboard that a unit test cannot reach: a table that is deliberately public
 * sitting next to three that are deliberately not.
 *
 * <p>Both halves are asserted here, and that is the point. The public exception is only defensible
 * if the filtered tables really are filtered — a test that only checked the board was readable
 * would pass just as happily on a schema where nothing was scoped at all.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LeaderboardTenancyIntegrationTest extends ContainerIntegrationBase {

    private static final String SEASON = "Winter 2026";
    private static final String OTHER_SEASON = "Fall 2026";

    @Autowired private EntityManager em;
    @Autowired private StudentRepo studentRepo;
    @Autowired private LeaderboardEntryRepository entries;
    @Autowired private LeaderboardProfileRepository profiles;
    @Autowired private SeasonBaselineRepository baselines;
    @Autowired private TranscriptUploadRepository uploads;
    @Autowired private TenantScope tenantScope;

    private Long studentA;
    private Long studentB;

    @BeforeEach
    void seed() {
        studentA = UserContext.callAsSystem(() -> studentRepo.save(new Student()).getId());
        studentB = UserContext.callAsSystem(() -> studentRepo.save(new Student()).getId());

        seedEntrant(studentA, "alpha", new BigDecimal("8000.00"), EntryStatus.ACTIVE);
        seedEntrant(studentB, "bravo", new BigDecimal("9500.00"), EntryStatus.ACTIVE);

        em.flush();
        // Load-bearing: without this the seeded rows sit in the first-level cache and are returned
        // with no SELECT, so the isolation assertions would pass for the wrong reason.
        em.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // -------------------------------------------------------------- the deliberate exception

    @Test
    @DisplayName("the board is readable across tenants, because it is a board")
    void everyStudentSeesEveryEntry() {
        UserContext.runAs(null, studentA, () -> {
            List<LeaderboardEntry> board =
                    entries.findBySeasonAndStatusOrderByScoreDescHandleAsc(SEASON, EntryStatus.ACTIVE);

            assertThat(board)
                    .as("a leaderboard that only shows you your own row is not a leaderboard")
                    .extracting(LeaderboardEntry::getHandle)
                    .containsExactly("bravo", "alpha");
        });
    }

    @Test
    @DisplayName("another student's entry is reachable by id too")
    void entryIsNotFilteredOnLoadByKey() {
        Long entryOfB = UserContext.callAsSystem(() ->
                entries.findByStudentIdAndSeason(studentB, SEASON).orElseThrow().getId());

        UserContext.runAs(null, studentA, () ->
                assertThat(entries.findById(entryOfB)).isPresent());
    }

    // ------------------------------------------------------------- and the three that are not

    @Test
    @DisplayName("another student's profile is invisible")
    void profileIsFiltered() {
        UserContext.runAs(null, studentA, () -> {
            assertThat(profiles.findAll())
                    .extracting(LeaderboardProfile::getOwnerId)
                    .containsOnly(studentA);
            assertThat(profiles.findByOwnerId(studentB)).isEmpty();
        });
    }

    @Test
    @DisplayName("another student's frozen baseline is invisible — it holds real grades")
    void seasonBaselineIsFiltered() {
        UserContext.runAs(null, studentA, () -> {
            assertThat(baselines.findAll())
                    .extracting(SeasonBaseline::getOwnerId)
                    .containsOnly(studentA);
            assertThat(baselines.findByOwnerIdAndSeason(studentB, SEASON)).isEmpty();
        });
    }

    @Test
    @DisplayName("another student's transcript record is invisible")
    void transcriptUploadIsFiltered() {
        UserContext.runAs(null, studentA, () -> {
            assertThat(uploads.findAll())
                    .extracting(TranscriptUpload::getOwnerId)
                    .containsOnly(studentA);
            assertThat(uploads.findByFileSha256("hash-of-b")).isEmpty();
        });
    }

    // --------------------------------------------------------------------- cross-tenant reads

    @Test
    @DisplayName("the duplicate-transcript check only works unfiltered, and does work unfiltered")
    void duplicateHashIsFoundOnlyAcrossTenants() {
        UserContext.runAs(null, studentA, () -> {
            // Filtered, the one thing being looked for is exactly what is hidden.
            assertThat(uploads.findByFileSha256("hash-of-b")).isEmpty();

            List<TranscriptUpload> everywhere =
                    tenantScope.unfiltered(() -> uploads.findByFileSha256("hash-of-b"));

            assertThat(everywhere)
                    .as("without the unfiltered scope the fraud check is a no-op that always passes")
                    .extracting(TranscriptUpload::getOwnerId)
                    .containsExactly(studentB);
        });
    }

    @Test
    @DisplayName("handle uniqueness only sees the whole system unfiltered")
    void handleUniquenessNeedsTheUnfilteredScope() {
        UserContext.runAs(null, studentA, () -> {
            assertThat(profiles.existsByHandleIgnoreCase("bravo"))
                    .as("filtered, every other student's handle looks free")
                    .isFalse();
            assertThat(tenantScope.unfiltered(() -> profiles.existsByHandleIgnoreCase("BRAVO")))
                    .as("and case-insensitively, because two cases are one handle to a reader")
                    .isTrue();
        });
    }

    /**
     * The failure mode {@code LeaderboardRankingService} documents: with the filter on and no tenant
     * bound, {@code findBySeason} returns nothing and the scheduled job silently rewrites an empty
     * board rather than failing.
     */
    @Test
    @DisplayName("the ranking job's read sees every student")
    void rankingJobReadIsCrossTenant() {
        List<SeasonBaseline> all = tenantScope.unfiltered(() -> baselines.findBySeason(SEASON));

        assertThat(all).extracting(SeasonBaseline::getOwnerId)
                .containsExactlyInAnyOrder(studentA, studentB);
    }

    // ------------------------------------------------------------------------ schema contract

    @Test
    @DisplayName("a second entry for the same student and season is rejected")
    void entryIsUniquePerStudentAndSeason() {
        UserContext.runAsSystem(() -> {
            LeaderboardEntry duplicate = new LeaderboardEntry();
            duplicate.setStudentId(studentA);
            duplicate.setSeason(SEASON);
            duplicate.setHandle("alpha-again");
            duplicate.setStatus(EntryStatus.ACTIVE);
            duplicate.setScoreMode(ScoreMode.GROWTH);
            duplicate.setScore(BigDecimal.ZERO);
            duplicate.setAmbition(BigDecimal.ZERO);
            duplicate.setBehaviorScore(BigDecimal.ONE);

            // The INSERT lands on save() rather than on flush(): GenerationType.IDENTITY has to
            // round-trip to the database to learn the key, so persist cannot be deferred.
            assertThatThrownBy(() -> entries.save(duplicate))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_leaderboard_entry_student_season");
        });
    }

    @Test
    @DisplayName("a second frozen baseline for the same season is rejected")
    void baselineIsWriteOncePerSeason() {
        UserContext.runAs(null, studentA, () -> {
            SeasonBaseline second = new SeasonBaseline();
            second.setSeason(SEASON);
            second.setBaselineGpa12(new BigDecimal("4.00"));
            second.setTargetGpa12(new BigDecimal("12.00"));
            second.setScoreMode(ScoreMode.GROWTH);

            assertThatThrownBy(() -> baselines.save(second))
                    .as("the constraint is what makes freezing structural rather than a convention")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_season_baseline_student_season");
        });
    }

    @Test
    @DisplayName("a different season is a different entry, so re-onboarding is possible")
    void aNewSeasonIsANewFreeze() {
        UserContext.runAs(null, studentA, () -> {
            SeasonBaseline next = new SeasonBaseline();
            next.setSeason(OTHER_SEASON);
            next.setBaselineGpa12(new BigDecimal("9.00"));
            next.setTargetGpa12(new BigDecimal("11.00"));
            next.setScoreMode(ScoreMode.GROWTH);

            baselines.save(next);
            em.flush();

            assertThat(baselines.findByOwnerIdAndSeason(studentA, OTHER_SEASON)).isPresent();
        });
    }

    @Test
    @DisplayName("the encrypted GPAs round-trip back through the converter")
    void encryptedColumnsRoundTrip() {
        UserContext.runAs(null, studentA, () -> {
            SeasonBaseline frozen = baselines.findByOwnerIdAndSeason(studentA, SEASON).orElseThrow();

            assertThat(frozen.getBaselineGpa12()).isEqualByComparingTo(new BigDecimal("8.00"));
            assertThat(frozen.getTargetGpa12()).isEqualByComparingTo(new BigDecimal("10.00"));

            // Ciphertext on disk, plaintext in the entity. If this ever comes back as "8.00" the
            // converter has silently stopped being applied.
            Object stored = em.createNativeQuery(
                            "select baseline_gpa12 from season_baseline where id = :id")
                    .setParameter("id", frozen.getId())
                    .getSingleResult();
            assertThat(stored.toString()).isNotEqualTo("8.00");
        });
    }

    @Test
    @DisplayName("a withdrawn entry never appears on the board, whatever it would have scored")
    void withdrawnEntriesAreUnranked() {
        UserContext.runAsSystem(() -> {
            // B is top of the board. Withdrawing has to remove them from it even so.
            LeaderboardEntry topOfBoard = entries.findByStudentIdAndSeason(studentB, SEASON).orElseThrow();
            topOfBoard.setStatus(EntryStatus.WITHDRAWN);
            entries.save(topOfBoard);
            em.flush();
            em.clear();
        });

        UserContext.runAs(null, studentA, () ->
                assertThat(entries.findBySeasonAndStatusOrderByScoreDescHandleAsc(SEASON, EntryStatus.ACTIVE))
                        .extracting(LeaderboardEntry::getHandle)
                        .containsExactly("alpha"));
    }

    // ------------------------------------------------------------------------------ fixtures

    private void seedEntrant(Long studentId, String handle, BigDecimal score, EntryStatus status) {
        UserContext.runAs(null, studentId, () -> {
            LeaderboardProfile profile = new LeaderboardProfile();
            profile.setHandle(handle);
            profile.setOptedInAt(Instant.now());
            profile.setRulesAcceptedAt(Instant.now());
            profiles.save(profile);

            SeasonBaseline baseline = new SeasonBaseline();
            baseline.setSeason(SEASON);
            baseline.setBaselineGpa12(new BigDecimal("8.00"));
            baseline.setTargetGpa12(new BigDecimal("10.00"));
            baseline.setScoreMode(ScoreMode.GROWTH);
            baselines.save(baseline);

            TranscriptUpload upload = new TranscriptUpload();
            upload.setSeason(SEASON);
            upload.setFileSha256("hash-of-" + (studentId.equals(studentA) ? "a" : "b"));
            upload.setParsedGpa12(new BigDecimal("8.00"));
            upload.setUploadedAt(Instant.now());
            uploads.save(upload);

            LeaderboardEntry entry = new LeaderboardEntry();
            entry.setStudentId(studentId);
            entry.setSeason(SEASON);
            entry.setHandle(handle);
            entry.setStatus(status);
            entry.setScoreMode(ScoreMode.GROWTH);
            entry.setScore(score);
            entry.setAmbition(new BigDecimal("0.500"));
            entry.setBehaviorScore(BigDecimal.ONE);
            entry.setBehaviorFlags(0);
            entry.setComputedAt(Instant.now());
            entries.save(entry);
        });
    }
}
