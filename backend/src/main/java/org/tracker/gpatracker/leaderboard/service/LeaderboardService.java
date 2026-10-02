package org.tracker.gpatracker.leaderboard.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.leaderboard.dto.LeaderboardResponse;
import org.tracker.gpatracker.leaderboard.dto.LeaderboardRowDTO;
import org.tracker.gpatracker.leaderboard.dto.LeaderboardStatusResponse;
import org.tracker.gpatracker.leaderboard.dto.RankSampleDTO;
import org.tracker.gpatracker.leaderboard.model.EntryStatus;
import org.tracker.gpatracker.leaderboard.model.LeaderboardEntry;
import org.tracker.gpatracker.leaderboard.model.LeaderboardProfile;
import org.tracker.gpatracker.leaderboard.model.SeasonBaseline;
import org.tracker.gpatracker.leaderboard.repository.LeaderboardEntryRepository;
import org.tracker.gpatracker.leaderboard.repository.LeaderboardProfileRepository;
import org.tracker.gpatracker.leaderboard.repository.LeaderboardRankHistoryRepository;
import org.tracker.gpatracker.leaderboard.repository.SeasonBaselineRepository;
import org.tracker.gpatracker.leaderboard.repository.TranscriptUploadRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Reading the board.
 *
 * <p>The one place in this codebase, alongside the shared syllabus catalog, where a read
 * deliberately crosses tenants — {@code LeaderboardEntry} is not filtered, because a board that
 * only shows you your own row is not a board. What keeps that safe is that nothing on the entry is
 * private: the baseline and target that genuinely are grades live on {@code SeasonBaseline}, which
 * is filtered like everything else.
 */
@Service
public class LeaderboardService {

    /** How many rows the board returns by default. */
    public static final int DEFAULT_LIMIT = 50;

    private static final int MAX_LIMIT = 200;

    /** Points on the sparkline. Seven runs is a little over two days at three runs a day. */
    private static final int HISTORY_POINTS = 7;

    private final LeaderboardEntryRepository entries;
    private final LeaderboardProfileRepository profiles;
    private final SeasonBaselineRepository baselines;
    private final TranscriptUploadRepository uploads;
    private final LeaderboardRankHistoryRepository history;
    private final LeaderboardOnboardingService onboarding;
    private final StudentService studentService;

    @Value("${app.current-term}")
    private String season;

    public LeaderboardService(LeaderboardEntryRepository entries,
                              LeaderboardProfileRepository profiles,
                              SeasonBaselineRepository baselines,
                              TranscriptUploadRepository uploads,
                              LeaderboardRankHistoryRepository history,
                              LeaderboardOnboardingService onboarding,
                              StudentService studentService) {
        this.entries = entries;
        this.profiles = profiles;
        this.baselines = baselines;
        this.uploads = uploads;
        this.history = history;
        this.onboarding = onboarding;
        this.studentService = studentService;
    }

    /**
     * The top of the board, plus the caller's own row wherever it happens to sit.
     *
     * <p>Own row always, even from four hundredth place. A visibly unreachable top rank is what
     * makes ordinary students disengage, and disengagement corrupts the exact signal v2 exists to
     * collect — so the number that is actually theirs is never off-screen.
     */
    @Transactional(readOnly = true)
    public LeaderboardResponse board(Integer limit) {
        Long studentId = studentService.getStudentID();
        int rows = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);

        // Ranked in one read rather than paged, because a rank is a position in the whole season
        // and cannot be derived from a page of it. Bounded by the number of students in a term.
        List<LeaderboardEntry> ranked =
                entries.findBySeasonAndStatusOrderByScoreDescHandleAsc(season, EntryStatus.ACTIVE);

        List<LeaderboardRowDTO> top = new ArrayList<>();
        LeaderboardRowDTO mine = null;
        Instant computedAt = null;

        for (int i = 0; i < ranked.size(); i++) {
            LeaderboardEntry entry = ranked.get(i);
            boolean isMe = studentId.equals(entry.getStudentId());
            LeaderboardRowDTO row = toRow(entry, i + 1, isMe);

            if (i < rows) {
                top.add(row);
            }
            if (isMe) {
                mine = row;
            }
            if (entry.getComputedAt() != null
                    && (computedAt == null || entry.getComputedAt().isAfter(computedAt))) {
                computedAt = entry.getComputedAt();
            }
        }

        return new LeaderboardResponse(season, top, mine, ranked.size(), computedAt);
    }

    /** Whether the caller is on the board, and under what terms. Cheap enough to poll on open. */
    @Transactional(readOnly = true)
    public LeaderboardStatusResponse status() {
        Long studentId = studentService.getStudentID();
        Optional<LeaderboardEntry> entry = entries.findByStudentIdAndSeason(studentId, season);

        if (entry.isEmpty()) {
            boolean submitted = uploads
                    .findFirstByOwnerIdAndSeasonOrderByIdDesc(studentId, season).isPresent();
            return LeaderboardStatusResponse.notJoined(season, submitted);
        }

        LeaderboardEntry row = entry.get();
        LeaderboardProfile profile = profiles.findByOwnerId(studentId).orElse(null);
        Optional<SeasonBaseline> baseline = baselines.findByOwnerIdAndSeason(studentId, season);

        // Newest first out of the index, reversed here: a line reads left to right in time order.
        List<RankSampleDTO> samples = new ArrayList<>(
                history.findByStudentIdAndSeasonOrderByComputedAtDesc(
                                studentId, season, PageRequest.of(0, HISTORY_POINTS))
                        .stream()
                        .map(sample -> new RankSampleDTO(sample.getRank(), sample.getComputedAt()))
                        .toList());
        Collections.reverse(samples);

        return new LeaderboardStatusResponse(
                season,
                true,
                row.getStatus(),
                row.getHandle(),
                row.getScoreMode(),
                row.getScore(),
                baseline.map(frozen -> LeaderboardScoring.ceiling(
                                frozen.getScoreMode(),
                                frozen.getBaselineGpa12().doubleValue(),
                                frozen.getTargetGpa12().doubleValue()))
                        .orElse(null),
                onboarding.canChangeHandle(studentId, profile),
                true,
                row.getComputedAt(),
                profile == null ? null : profile.getAvatar(),
                row.getRank(),
                row.rankDelta(),
                samples);
    }

    /**
     * The target frozen when the student joined, while they are actively on this season's board.
     * Empty when they never joined or have withdrawn, in which case their personal target applies.
     *
     * <p>Read from {@code SeasonBaseline}, the same row scoring uses, so what the dashboard shows is
     * exactly what the board is measuring against.
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> lockedTargetGpa12(Long studentId) {
        boolean active = entries.findByStudentIdAndSeason(studentId, season)
                .map(entry -> entry.getStatus() == EntryStatus.ACTIVE)
                .orElse(false);
        if (!active) {
            return Optional.empty();
        }
        return baselines.findByOwnerIdAndSeason(studentId, season).map(SeasonBaseline::getTargetGpa12);
    }

    /**
     * The rank is the live position in this read, not the one the job stored.
     *
     * <p>The two agree whenever nothing has changed since the last run, and when they disagree the
     * live one is right: a student who withdrew an hour ago should not still be occupying a place
     * on the board. The stored rank's job is only to be the thing the <em>next</em> run compares
     * against, which is why the delta comes off the entry while the position does not.
     */
    private static LeaderboardRowDTO toRow(LeaderboardEntry entry, int rank, boolean isMe) {
        return new LeaderboardRowDTO(rank, entry.getHandle(), entry.getScore(),
                entry.getScoreMode(), isMe, entry.getAvatar(), entry.rankDelta());
    }
}
