package org.tracker.gpatracker.leaderboard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.tracker.gpatracker.assessmenttable.model.AssessmentTableDocument;
import org.tracker.gpatracker.leaderboard.model.EntryStatus;
import org.tracker.gpatracker.leaderboard.model.LeaderboardEntry;
import org.tracker.gpatracker.leaderboard.model.LeaderboardRankHistory;
import org.tracker.gpatracker.leaderboard.model.SeasonBaseline;
import org.tracker.gpatracker.leaderboard.repository.LeaderboardEntryRepository;
import org.tracker.gpatracker.leaderboard.repository.LeaderboardRankHistoryRepository;
import org.tracker.gpatracker.leaderboard.repository.SeasonBaselineRepository;
import org.tracker.gpatracker.tenancy.TenantScope;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rewrites every entry's score and position, three times a day.
 *
 * <p>Scoring is a batch job rather than a request-time computation because it is a cross-tenant
 * read of several tables per student, and because a board that moves the instant
 * you type a grade invites exactly the refresh-driven behaviour this feature is not trying to
 * encourage. The one exception is the moment a student joins, where an immediate score is the
 * difference between landing on a board and landing on a blank row until the next run.
 *
 * <p>Two things start a run, and both end up in {@link #recompute()}:
 *
 * <ul>
 *   <li>the in-process cron below, on Toronto time. On its own this is not reliable in production:
 *       Cloud Run has no minimum instances, so at 06:00 there may be no instance alive to fire it,
 *       and an idle instance has its CPU throttled;</li>
 *   <li>{@code POST /internal/jobs/leaderboard-recompute}, which Cloud Scheduler calls on the same
 *       schedule. The request itself wakes an instance and keeps CPU allocated until the run
 *       finishes, which is why the run is synchronous rather than handed to a background thread.</li>
 * </ul>
 *
 * <p>If both fire on the same instance, the second is skipped. On two different instances both
 * run, which for scoring only costs work: a rescore is a pure function of the stored data, so
 * running it twice writes the same scores twice.
 *
 * <p>Ranking does not have that property, because each pass moves the previous pass's rank into
 * {@code previousRank}, so a second pass would erase the movement the first one recorded. It
 * carries its own guard, in the database rather than in a field, so that two instances can see it.
 */
@Service
public class LeaderboardRankingService {

    private static final Logger logger = LoggerFactory.getLogger(LeaderboardRankingService.class);

    private final SeasonBaselineRepository baselines;
    private final LeaderboardEntryRepository entries;
    private final LeaderboardRankHistoryRepository history;
    private final ProjectedGpaService projectedGpa;
    private final TenantScope tenantScope;
    private final TransactionTemplate transaction;

    /** Stops a cron tick and an HTTP trigger that land together from running the sweep twice. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * How close together two sweeps have to be before the second declines to re-rank.
     *
     * <p>Sized against the gap between scheduled runs, which is eight hours, not against how long
     * a sweep takes. The two triggers fire on the same schedule, so a duplicate arrives seconds
     * later; nothing legitimate arrives within ten minutes of the last run.
     */
    private static final Duration RANK_COOLDOWN = Duration.ofMinutes(10);

    @Value("${app.current-term}")
    private String season;

    public LeaderboardRankingService(SeasonBaselineRepository baselines,
                                     LeaderboardEntryRepository entries,
                                     LeaderboardRankHistoryRepository history,
                                     ProjectedGpaService projectedGpa,
                                     TenantScope tenantScope,
                                     PlatformTransactionManager transactionManager) {
        this.baselines = baselines;
        this.entries = entries;
        this.history = history;
        this.projectedGpa = projectedGpa;
        this.tenantScope = tenantScope;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * What one sweep did.
     *
     * @param skipped true when another sweep was already running on this instance, in which case
     *                nothing was read or written
     */
    public record RecomputeResult(String season, int rescored, int total, long durationMs, boolean skipped) {
    }

    /** The in-process trigger. Times and zone are configurable; see application.properties. */
    @Scheduled(cron = "${leaderboard.recompute.cron}", zone = "${leaderboard.recompute.zone}")
    public void scheduledRecompute() {
        recompute();
    }

    /**
     * Rescores every active entry in the current season.
     *
     * <p>Genuinely cross-tenant, so it runs unfiltered, and that is not optional. With the owner
     * filter enabled and no tenant bound, {@code findBySeason} would return nothing and the job
     * would silently rewrite an empty board rather than failing.
     *
     * <p>The transaction is opened here with a template rather than by {@code @Transactional},
     * because {@link #scheduledRecompute()} calls this on {@code this}, which bypasses the proxy
     * that annotation relies on.
     */
    public RecomputeResult recompute() {
        if (!running.compareAndSet(false, true)) {
            logger.info("Leaderboard recompute already running on this instance; skipping");
            return new RecomputeResult(season, 0, 0, 0, true);
        }
        long started = System.currentTimeMillis();
        try {
            RecomputeResult result = transaction.execute(status -> tenantScope.unfiltered(this::rankAcrossAllTenants));
            long duration = System.currentTimeMillis() - started;
            logger.info("Leaderboard recompute for season {}: {} of {} entries rescored in {} ms",
                    season, result.rescored(), result.total(), duration);
            return new RecomputeResult(season, result.rescored(), result.total(), duration, false);
        } finally {
            running.set(false);
        }
    }

    private RecomputeResult rankAcrossAllTenants() {
        Instant now = Instant.now();
        List<SeasonBaseline> season = baselines.findBySeason(this.season);
        int rescored = 0;

        for (SeasonBaseline baseline : season) {
            try {
                if (rescore(baseline, now)) {
                    rescored++;
                }
            } catch (RuntimeException e) {
                // One student's malformed table must not cost every other student their update.
                logger.error("Leaderboard rescore failed for student {} in season {}",
                        baseline.getOwnerId(), baseline.getSeason(), e);
            }
        }

        assignRanks(now);
        return new RecomputeResult(this.season, rescored, season.size(), 0, false);
    }

    /**
     * Turns the scores just written into positions, and records them.
     *
     * <p>Runs after every score is final, because a rank is a position among all of them and cannot
     * be decided one student at a time.
     *
     * <p>Reads through {@code findBySeasonAndStatusOrderByScoreDescHandleAsc}, which is the exact
     * method {@code LeaderboardService.board()} ranks with. That is not a convenience: if the two
     * sorted differently, the rank stored here would be a position no student was ever shown, and
     * the trend chip would compare against a number that never appeared on screen.
     *
     * <p>Unlike rescoring, this is not safe to run twice. Scores are a pure function of stored
     * data, so writing them again writes the same numbers; ranks are not, because each pass moves
     * the last pass's rank into {@code previousRank}. A second pass minutes after the first would
     * therefore set previous equal to current, report every student as having held position, and
     * add a duplicate point to every sparkline. The in-process guard in {@link #recompute()} only
     * covers one instance, so the second guard is in the database where both can see it.
     */
    private void assignRanks(Instant now) {
        if (history.existsBySeasonAndComputedAtAfter(season, now.minus(RANK_COOLDOWN))) {
            logger.info("Season {} was already ranked within the last {}; leaving ranks alone",
                    season, RANK_COOLDOWN);
            return;
        }

        List<LeaderboardEntry> ranked =
                entries.findBySeasonAndStatusOrderByScoreDescHandleAsc(season, EntryStatus.ACTIVE);

        List<LeaderboardRankHistory> samples = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            LeaderboardEntry entry = ranked.get(i);
            int rank = i + 1;

            // Last run's position becomes the thing this run is measured against, which has to
            // happen before rank is overwritten or both columns end up holding the same number.
            entry.setPreviousRank(entry.getRank());
            entry.setRank(rank);
            samples.add(new LeaderboardRankHistory(
                    entry.getStudentId(), season, rank, entry.getScore(), now));
        }

        entries.saveAll(ranked);
        history.saveAll(samples);

        // Only the newest few samples per student are ever read, so everything belonging to a
        // season that has ended is dead weight from the moment the term rolls over.
        int pruned = history.deleteFromClosedSeasons(season);
        if (pruned > 0) {
            logger.info("Pruned {} rank history rows from closed seasons", pruned);
        }
    }

    /**
     * Recomputes one entry in place.
     *
     * <p>Callable both from the scheduled sweep (inside a system scope) and from the join flow (as
     * the student themselves): every read below is explicitly keyed on the owner it wants, so it
     * behaves the same either way.
     *
     * @return false when there is nothing ranked to update — never joined, or withdrawn
     */
    boolean rescore(SeasonBaseline baseline, Instant now) {
        LeaderboardEntry entry = entries
                .findByStudentIdAndSeason(baseline.getOwnerId(), baseline.getSeason())
                .orElse(null);

        // A withdrawn entry keeps its last score and stops being touched. Nothing is recorded
        // against the student, and rejoining picks up the frozen baseline this row still points at.
        if (entry == null || entry.getStatus() != EntryStatus.ACTIVE) {
            return false;
        }

        List<AssessmentTableDocument> tables =
                projectedGpa.currentTerm(baseline.getOwnerId(), baseline.getSeason());
        int flags = BehaviorScoring.flagCount(tables);
        double behavior = BehaviorScoring.behaviorScore(flags);

        double baselineGpa = baseline.getBaselineGpa12().doubleValue();
        double target = baseline.getTargetGpa12().doubleValue();
        double projected = projectedGpa
                .projectedGpa12(baseline.getOwnerId(), baseline.getSeason(), now)
                .doubleValue();

        entry.setScore(LeaderboardScoring.score(
                baseline.getScoreMode(), baselineGpa, target, projected, behavior));
        entry.setScoreMode(baseline.getScoreMode());
        entry.setAmbition(ratio(LeaderboardScoring.ambition(baselineGpa, target)));
        entry.setBehaviorScore(ratio(behavior));
        entry.setBehaviorFlags(flags);
        entry.setComputedAt(now);

        entries.save(entry);
        return true;
    }

    /** Rescores one student now, for the moment they join. */
    @Transactional
    public void rescoreNow(Long studentId) {
        baselines.findByOwnerIdAndSeason(studentId, season)
                .ifPresent(baseline -> rescore(baseline, Instant.now()));
    }

    /**
     * The 0-to-1 figures share a numeric(4,3) shape, so they are rounded the same way — and, like
     * {@code LeaderboardScoring.round}, through an intermediate scale that absorbs float error
     * before the stored one decides an exact half.
     */
    private static BigDecimal ratio(double value) {
        return BigDecimal.valueOf(value)
                .setScale(6, RoundingMode.HALF_UP)
                .setScale(3, RoundingMode.HALF_UP);
    }
}
