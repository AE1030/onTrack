package org.tracker.gpatracker.leaderboard.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.accounts.service.TranscriptUploadService;
import org.tracker.gpatracker.leaderboard.dto.ChangeAvatarRequest;
import org.tracker.gpatracker.leaderboard.dto.JoinLeaderboardRequest;
import org.tracker.gpatracker.leaderboard.model.EntryStatus;
import org.tracker.gpatracker.leaderboard.model.LeaderboardAvatar;
import org.tracker.gpatracker.leaderboard.model.LeaderboardProfile;
import org.tracker.gpatracker.leaderboard.model.LeaderboardEntry;
import org.tracker.gpatracker.leaderboard.model.ScoreMode;
import org.tracker.gpatracker.leaderboard.model.SeasonBaseline;
import org.tracker.gpatracker.leaderboard.model.TranscriptUpload;
import org.tracker.gpatracker.leaderboard.repository.LeaderboardEntryRepository;
import org.tracker.gpatracker.leaderboard.repository.LeaderboardProfileRepository;
import org.tracker.gpatracker.leaderboard.repository.SeasonBaselineRepository;
import org.tracker.gpatracker.leaderboard.repository.TranscriptUploadRepository;
import org.tracker.gpatracker.tenancy.TenantScope;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The gate, its refusals, and what it refuses to overwrite.
 *
 * <p>Three groups, matching the three things onboarding is actually for: admitting the right
 * students, freezing what they joined with, and letting them leave without consequence.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaderboardOnboardingServiceTest {

    private static final String SEASON = "Winter 2026";
    private static final Long STUDENT = 7L;
    private static final Long OTHER_STUDENT = 8L;

    @Mock private TranscriptUploadService transcripts;
    @Mock private TranscriptUploadRepository uploads;
    @Mock private SeasonBaselineRepository baselines;
    @Mock private LeaderboardProfileRepository profiles;
    @Mock private LeaderboardEntryRepository entries;
    @Mock private LeaderboardRankingService ranking;
    @Mock private StudentService studentService;
    @Mock private TenantScope tenantScope;

    private LeaderboardOnboardingService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        service = new LeaderboardOnboardingService(transcripts, uploads, baselines, profiles,
                entries, ranking, studentService, tenantScope);

        // @Value does not apply in a unit test, so the season is set the way
        // AssessmentTableServiceTest sets currentTerm.
        Field season = LeaderboardOnboardingService.class.getDeclaredField("season");
        season.setAccessible(true);
        season.set(service, SEASON);

        when(studentService.getStudentID()).thenReturn(STUDENT);
        // The real TenantScope disables the Hibernate filter and runs the body. Here the body is
        // all that matters -- what is being tested is that the cross-tenant reads happen at all.
        when(tenantScope.unfiltered(any(Supplier.class)))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        when(baselines.save(any())).thenAnswer(call -> call.getArgument(0));
        when(profiles.save(any())).thenAnswer(call -> call.getArgument(0));
        when(entries.save(any())).thenAnswer(call -> call.getArgument(0));
        when(uploads.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    // ---------------------------------------------------------------- OnboardingValidationTest

    @Nested
    @DisplayName("who gets through the gate")
    class OnboardingValidation {

        @Test
        @DisplayName("a target under a point of climb selects MAINTENANCE rather than failing")
        void flatTargetIsNotAGrowthEntry() {
            transcriptOnFile(new BigDecimal("8.00"));

            service.join(new JoinLeaderboardRequest(new BigDecimal("8.50"), "ahmed", true));

            assertThat(frozenBaseline().getScoreMode()).isEqualTo(ScoreMode.MAINTENANCE);
        }

        @Test
        @DisplayName("a full point of climb selects GROWTH")
        void aRealGoalIsAGrowthEntry() {
            transcriptOnFile(new BigDecimal("8.00"));

            service.join(new JoinLeaderboardRequest(new BigDecimal("9.00"), "ahmed", true));

            assertThat(frozenBaseline().getScoreMode()).isEqualTo(ScoreMode.GROWTH);
        }

        @Test
        @DisplayName("a missing target blocks the join rather than producing a scoreless entry")
        void missingTargetIsRefused() {
            transcriptOnFile(new BigDecimal("8.00"));

            assertThatThrownBy(() -> service.join(new JoinLeaderboardRequest(null, "ahmed", true)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);

            verify(baselines, never()).save(any());
        }

        @Test
        @DisplayName("a target below the baseline is not a goal")
        void targetBelowBaselineIsRefused() {
            transcriptOnFile(new BigDecimal("8.00"));

            assertThatThrownBy(() ->
                    service.join(new JoinLeaderboardRequest(new BigDecimal("6.00"), "ahmed", true)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("a missing baseline blocks the join rather than trusting a typed number")
        void missingBaselineIsRefused() {
            when(baselines.findByOwnerIdAndSeason(STUDENT, SEASON)).thenReturn(Optional.empty());
            when(uploads.findFirstByOwnerIdAndSeasonOrderByIdDesc(STUDENT, SEASON))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.join(new JoinLeaderboardRequest(new BigDecimal("9.00"), "ahmed", true)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.PRECONDITION_REQUIRED);
        }

        @Test
        @DisplayName("an unparseable transcript surfaces a fixable error, not a fallback baseline")
        void unparseableTranscriptIsRefused() throws IOException {
            when(baselines.findByOwnerIdAndSeason(STUDENT, SEASON)).thenReturn(Optional.empty());
            when(transcripts.parseGpa12(any()))
                    .thenThrow(new IllegalStateException("No courses found in uploaded file."));

            assertThatThrownBy(() -> service.submitTranscript(pdf("not-a-transcript")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNPROCESSABLE_ENTITY);

            verify(uploads, never()).save(any());
        }

        @Test
        @DisplayName("a transcript already seen under another account is blocked at the door")
        void duplicateTranscriptIsRefused() {
            when(baselines.findByOwnerIdAndSeason(STUDENT, SEASON)).thenReturn(Optional.empty());
            when(uploads.findByFileSha256(anyString())).thenReturn(List.of(uploadOwnedBy(OTHER_STUDENT)));

            assertThatThrownBy(() -> service.submitTranscript(pdf("shared-transcript")))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);

            verify(uploads, never()).save(any());
        }

        @Test
        @DisplayName("the same student re-uploading their own file is not a duplicate")
        void ownTranscriptIsNotADuplicate() throws IOException {
            when(baselines.findByOwnerIdAndSeason(STUDENT, SEASON)).thenReturn(Optional.empty());
            when(uploads.findByFileSha256(anyString())).thenReturn(List.of(uploadOwnedBy(STUDENT)));
            when(transcripts.parseGpa12(any())).thenReturn(new BigDecimal("8.00"));

            service.submitTranscript(pdf("my-transcript"));

            verify(uploads).save(any());
        }

        @Test
        @DisplayName("a first-year joins on a baseline of zero, with no ambition bonus")
        void firstYearJoins() {
            transcriptOnFile(BigDecimal.ZERO);

            service.join(new JoinLeaderboardRequest(new BigDecimal("8.00"), "firstyear", true));

            SeasonBaseline frozen = frozenBaseline();
            assertThat(frozen.getBaselineGpa12()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(frozen.getScoreMode()).isEqualTo(ScoreMode.GROWTH);
            assertThat(LeaderboardScoring.ambition(0.0, 8.0)).isZero();
        }

        @Test
        @DisplayName("a baseline above 11.0 is admitted, in CEILING mode")
        void strongStudentJoins() {
            transcriptOnFile(new BigDecimal("11.50"));

            service.join(new JoinLeaderboardRequest(new BigDecimal("12.00"), "topofclass", true));

            assertThat(frozenBaseline().getScoreMode()).isEqualTo(ScoreMode.CEILING);
        }

        @Test
        @DisplayName("a handle already held by someone else is refused")
        void takenHandleIsRefused() {
            transcriptOnFile(new BigDecimal("8.00"));
            when(profiles.existsByHandleIgnoreCase("ahmed")).thenReturn(true);

            assertThatThrownBy(() ->
                    service.join(new JoinLeaderboardRequest(new BigDecimal("9.00"), "ahmed", true)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("a screened handle is refused")
        void screenedHandleIsRefused() {
            transcriptOnFile(new BigDecimal("8.00"));

            assertThatThrownBy(() ->
                    service.join(new JoinLeaderboardRequest(new BigDecimal("9.00"), "sh1thead", true)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST);
        }
    }

    // -------------------------------------------------------------------- BaselineFreezeTest

    @Nested
    @DisplayName("what joining freezes")
    class BaselineFreeze {

        @Test
        @DisplayName("a second transcript upload leaves the frozen baseline untouched")
        void secondUploadDoesNotMoveTheBaseline() throws IOException {
            when(baselines.findByOwnerIdAndSeason(STUDENT, SEASON))
                    .thenReturn(Optional.of(frozen(new BigDecimal("8.00"), new BigDecimal("10.00"))));

            var response = service.submitTranscript(pdf("a-better-transcript"));

            assertThat(response.baselineGpa12()).isEqualByComparingTo(new BigDecimal("8.00"));
            assertThat(response.frozen()).isTrue();
            verify(transcripts, never()).parseGpa12(any());
            verify(uploads, never()).save(any());
        }

        @Test
        @DisplayName("rejoining restores the original starting line rather than a fresh one")
        void rejoiningDoesNotRefreeze() {
            SeasonBaseline existing = frozen(new BigDecimal("8.00"), new BigDecimal("10.00"));
            when(baselines.findByOwnerIdAndSeason(STUDENT, SEASON)).thenReturn(Optional.of(existing));

            // A target the student would much rather have, arriving after a bad term.
            service.join(new JoinLeaderboardRequest(new BigDecimal("8.10"), "ahmed", true));

            verify(baselines, never()).save(any());
            assertThat(existing.getTargetGpa12()).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(existing.getScoreMode()).isEqualTo(ScoreMode.GROWTH);
        }

        @Test
        @DisplayName("the mode is frozen with the numbers that chose it")
        void modeIsFrozenToo() {
            transcriptOnFile(new BigDecimal("8.00"));

            service.join(new JoinLeaderboardRequest(new BigDecimal("11.00"), "ahmed", true));

            SeasonBaseline frozen = frozenBaseline();
            assertThat(frozen.getScoreMode()).isEqualTo(ScoreMode.GROWTH);
            assertThat(frozen.getBaselineGpa12()).isEqualByComparingTo(new BigDecimal("8.00"));
            assertThat(frozen.getTargetGpa12()).isEqualByComparingTo(new BigDecimal("11.00"));
        }

        @Test
        @DisplayName("joining scores the entry immediately rather than waiting for the nightly job")
        void joiningScoresRightAway() {
            transcriptOnFile(new BigDecimal("8.00"));

            service.join(new JoinLeaderboardRequest(new BigDecimal("9.00"), "ahmed", true));

            verify(ranking).rescoreNow(STUDENT);
        }
    }

    // --------------------------------------------------------------------------- ForfeitTest

    @Nested
    @DisplayName("leaving")
    class Forfeit {

        @Test
        @DisplayName("opting out moves the entry to WITHDRAWN and leaves no other mark")
        void withdrawingForfeitsAndNothingMore() {
            LeaderboardEntry entry = activeEntry();
            when(entries.findByStudentIdAndSeason(STUDENT, SEASON)).thenReturn(Optional.of(entry));

            service.withdraw();

            assertThat(entry.getStatus()).isEqualTo(EntryStatus.WITHDRAWN);
            assertThat(entry.getStatus().isRanked()).isFalse();
            // The frozen row and the profile both survive, which is what lets a rejoin restore the
            // same starting line -- and what stops cycling from resetting the handle allowance.
            verify(baselines, never()).delete(any());
            verify(profiles, never()).delete(any());
        }

        @Test
        @DisplayName("leaving hands the frozen target back as the editable personal target")
        void withdrawingKeepsTheTargetOnTheDashboard() {
            when(entries.findByStudentIdAndSeason(STUDENT, SEASON)).thenReturn(Optional.of(activeEntry()));
            when(baselines.findByOwnerIdAndSeason(STUDENT, SEASON))
                    .thenReturn(Optional.of(frozen(new BigDecimal("8.00"), new BigDecimal("10.00"))));

            service.withdraw();

            verify(studentService).setTargetGpa(
                    org.mockito.ArgumentMatchers.argThat(t4 -> t4.compareTo(new BigDecimal("3.70")) == 0),
                    org.mockito.ArgumentMatchers.argThat(t12 -> t12.compareTo(new BigDecimal("10.00")) == 0));
        }

        @Test
        @DisplayName("withdrawing without an entry is a no-op, not an error")
        void withdrawingTwiceIsHarmless() {
            when(entries.findByStudentIdAndSeason(STUDENT, SEASON)).thenReturn(Optional.empty());

            service.withdraw();

            verify(entries, never()).save(any());
        }

        @Test
        @DisplayName("rejoining reactivates the existing row rather than creating a second one")
        void rejoiningReusesTheEntry() {
            LeaderboardEntry withdrawn = activeEntry();
            withdrawn.setStatus(EntryStatus.WITHDRAWN);
            when(entries.findByStudentIdAndSeason(STUDENT, SEASON)).thenReturn(Optional.of(withdrawn));
            when(baselines.findByOwnerIdAndSeason(STUDENT, SEASON))
                    .thenReturn(Optional.of(frozen(new BigDecimal("8.00"), new BigDecimal("10.00"))));

            service.join(new JoinLeaderboardRequest(new BigDecimal("10.00"), "ahmed", true));

            assertThat(withdrawn.getStatus()).isEqualTo(EntryStatus.ACTIVE);
            // The score it carried into the withdrawal is kept rather than reset to zero; the
            // immediate rescore is what corrects it.
            assertThat(withdrawn.getScore()).isEqualByComparingTo(new BigDecimal("6000.00"));
        }
    }

    // ---------------------------------------------------------------------------- avatars

    @Nested
    @DisplayName("the face on the board")
    class Avatars {

        private final LeaderboardAvatar picked = new LeaderboardAvatar(3, 1, 7, 0, 2);

        @Test
        @DisplayName("an avatar chosen at join lands on the profile and on the published row")
        void joinStoresTheAvatar() {
            transcriptOnFile(new BigDecimal("8.00"));

            service.join(new JoinLeaderboardRequest(new BigDecimal("9.00"), "ahmed", true, picked));

            ArgumentCaptor<LeaderboardProfile> profile = ArgumentCaptor.forClass(LeaderboardProfile.class);
            verify(profiles).save(profile.capture());
            assertThat(profile.getValue().getAvatar()).isEqualTo(picked);

            ArgumentCaptor<LeaderboardEntry> entry = ArgumentCaptor.forClass(LeaderboardEntry.class);
            verify(entries).save(entry.capture());
            assertThat(entry.getValue().getAvatar()).isEqualTo(picked);
        }

        @Test
        @DisplayName("rejoining without an avatar keeps the one already on the profile")
        void rejoinKeepsTheAvatar() {
            LeaderboardProfile existing = new LeaderboardProfile();
            existing.setOwnerId(STUDENT);
            existing.setHandle("ahmed");
            existing.setAvatar(picked);
            when(profiles.findByOwnerId(STUDENT)).thenReturn(Optional.of(existing));

            LeaderboardEntry withdrawn = activeEntry();
            withdrawn.setStatus(EntryStatus.WITHDRAWN);
            when(entries.findByStudentIdAndSeason(STUDENT, SEASON)).thenReturn(Optional.of(withdrawn));
            when(baselines.findByOwnerIdAndSeason(STUDENT, SEASON))
                    .thenReturn(Optional.of(frozen(new BigDecimal("8.00"), new BigDecimal("10.00"))));

            service.join(new JoinLeaderboardRequest(new BigDecimal("10.00"), "ahmed", true));

            assertThat(existing.getAvatar()).isEqualTo(picked);
            assertThat(withdrawn.getAvatar()).isEqualTo(picked);
        }

        @Test
        @DisplayName("changing the avatar updates the published row immediately")
        void changeAvatarUpdatesTheBoard() {
            LeaderboardProfile existing = new LeaderboardProfile();
            existing.setOwnerId(STUDENT);
            existing.setHandle("ahmed");
            when(profiles.findByOwnerId(STUDENT)).thenReturn(Optional.of(existing));
            LeaderboardEntry entry = activeEntry();
            when(entries.findByStudentIdAndSeason(STUDENT, SEASON)).thenReturn(Optional.of(entry));

            service.changeAvatar(new ChangeAvatarRequest(picked));

            assertThat(existing.getAvatar()).isEqualTo(picked);
            assertThat(entry.getAvatar()).isEqualTo(picked);
        }

        @Test
        @DisplayName("choosing an avatar before joining is refused")
        void changeAvatarRequiresAProfile() {
            when(profiles.findByOwnerId(STUDENT)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.changeAvatar(new ChangeAvatarRequest(picked)))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    // ------------------------------------------------------------------------- fixtures

    /** A transcript has been submitted for the season, but nothing is frozen yet. */
    private void transcriptOnFile(BigDecimal baseline) {
        when(baselines.findByOwnerIdAndSeason(STUDENT, SEASON)).thenReturn(Optional.empty());

        TranscriptUpload upload = new TranscriptUpload();
        upload.setOwnerId(STUDENT);
        upload.setSeason(SEASON);
        upload.setParsedGpa12(baseline);
        when(uploads.findFirstByOwnerIdAndSeasonOrderByIdDesc(STUDENT, SEASON))
                .thenReturn(Optional.of(upload));
    }

    private SeasonBaseline frozenBaseline() {
        ArgumentCaptor<SeasonBaseline> captor = ArgumentCaptor.forClass(SeasonBaseline.class);
        verify(baselines).save(captor.capture());
        return captor.getValue();
    }

    private static SeasonBaseline frozen(BigDecimal baseline, BigDecimal target) {
        SeasonBaseline row = new SeasonBaseline();
        row.setOwnerId(STUDENT);
        row.setSeason(SEASON);
        row.setBaselineGpa12(baseline);
        row.setTargetGpa12(target);
        row.setScoreMode(LeaderboardScoring.modeFor(baseline.doubleValue(), target.doubleValue()));
        return row;
    }

    private static LeaderboardEntry activeEntry() {
        LeaderboardEntry entry = new LeaderboardEntry();
        entry.setStudentId(STUDENT);
        entry.setSeason(SEASON);
        entry.setHandle("ahmed");
        entry.setStatus(EntryStatus.ACTIVE);
        entry.setScoreMode(ScoreMode.GROWTH);
        entry.setScore(new BigDecimal("6000.00"));
        entry.setAmbition(new BigDecimal("0.500"));
        entry.setBehaviorScore(BigDecimal.ONE);
        return entry;
    }

    private static TranscriptUpload uploadOwnedBy(Long studentId) {
        TranscriptUpload upload = new TranscriptUpload();
        upload.setOwnerId(studentId);
        upload.setSeason(SEASON);
        upload.setFileSha256("irrelevant");
        upload.setParsedGpa12(new BigDecimal("8.00"));
        return upload;
    }

    private static MockMultipartFile pdf(String content) {
        return new MockMultipartFile("file", "transcript.pdf", "application/pdf", content.getBytes());
    }
}
