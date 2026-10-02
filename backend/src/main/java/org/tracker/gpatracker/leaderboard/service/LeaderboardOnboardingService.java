package org.tracker.gpatracker.leaderboard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.accounts.service.gpautils.GpaScale;
import org.tracker.gpatracker.accounts.service.TranscriptUploadService;
import org.tracker.gpatracker.leaderboard.dto.ChangeAvatarRequest;
import org.tracker.gpatracker.leaderboard.dto.ChangeHandleRequest;
import org.tracker.gpatracker.leaderboard.dto.HandleCheckResponse;
import org.tracker.gpatracker.leaderboard.dto.JoinLeaderboardRequest;
import org.tracker.gpatracker.leaderboard.dto.TargetPreviewResponse;
import org.tracker.gpatracker.leaderboard.dto.TranscriptBaselineResponse;
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
import org.tracker.gpatracker.tenancy.TenantScope;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The gate. Joining the leaderboard is not a welcome mat: it is the only moment the system can
 * establish a verified starting point, and everything downstream is undefined without one.
 *
 * <p>Three calls, in order, because the middle one cannot exist without the first:
 *
 * <ol>
 *   <li>{@link #submitTranscript} parses a PDF into a baseline and records its hash;</li>
 *   <li>{@link #preview} says what a candidate target would be worth against that baseline;</li>
 *   <li>{@link #join} freezes the baseline, the target and the mode, and publishes an entry.</li>
 * </ol>
 *
 * <p>Freezing matters as much as capturing. Both GPAs are copied into {@code SeasonBaseline} rather
 * than read live from {@code Student}: reading {@code targetGpa12} live would let a student inflate
 * their progress in week 11 by lowering the target, and a re-uploadable baseline would do the same
 * from the other end.
 */
@Service
public class LeaderboardOnboardingService {

    private static final Logger logger = LoggerFactory.getLogger(LeaderboardOnboardingService.class);

    private final TranscriptUploadService transcripts;
    private final TranscriptUploadRepository uploads;
    private final SeasonBaselineRepository baselines;
    private final LeaderboardProfileRepository profiles;
    private final LeaderboardEntryRepository entries;
    private final LeaderboardRankingService ranking;
    private final StudentService studentService;
    private final TenantScope tenantScope;

    @Value("${app.current-term}")
    private String season;

    public LeaderboardOnboardingService(TranscriptUploadService transcripts,
                                        TranscriptUploadRepository uploads,
                                        SeasonBaselineRepository baselines,
                                        LeaderboardProfileRepository profiles,
                                        LeaderboardEntryRepository entries,
                                        LeaderboardRankingService ranking,
                                        StudentService studentService,
                                        TenantScope tenantScope) {
        this.transcripts = transcripts;
        this.uploads = uploads;
        this.baselines = baselines;
        this.profiles = profiles;
        this.entries = entries;
        this.ranking = ranking;
        this.studentService = studentService;
        this.tenantScope = tenantScope;
    }

    // ------------------------------------------------------------------ step 1: transcript

    /**
     * Parses a transcript into this season's baseline.
     *
     * <p>The duplicate-hash check here is the only anti-fraud control v2 ships. It survives the cut
     * that removed everything else because it costs nothing: the upload happens at this point
     * anyway, and refusing at the door needs no status machine behind it.
     *
     * <p>A file that will not parse blocks the join with a fixable error rather than falling back to
     * a number the student typed. A wrong baseline is worse than no entry — it is invisible,
     * permanent for the season, and with settlement deferred to v3 nothing downstream will ever
     * correct it.
     */
    @Transactional
    public TranscriptBaselineResponse submitTranscript(MultipartFile file) {
        Long studentId = studentService.getStudentID();

        // Already frozen: a second upload in the same season is accepted and ignored, rather than
        // erroring, because the student has no way to know it would change nothing.
        Optional<SeasonBaseline> frozen = baselines.findByOwnerIdAndSeason(studentId, season);
        if (frozen.isPresent()) {
            return describe(frozen.get().getBaselineGpa12(), true);
        }

        byte[] bytes = read(file);
        String sha256 = sha256(bytes);
        rejectIfSeenUnderAnotherStudent(sha256, studentId);

        BigDecimal baseline = parse(file);

        TranscriptUpload upload = new TranscriptUpload();
        // Owner is stamped from the request context on persist — see UserOwnedEntity.
        upload.setSeason(season);
        upload.setFileSha256(sha256);
        upload.setParsedGpa12(baseline);
        upload.setUploadedAt(Instant.now());
        uploads.save(upload);

        logger.info("Leaderboard transcript accepted for student {} in season {}", studentId, season);
        return describe(baseline, false);
    }

    /**
     * Blocks a PDF that has already been used to join under a different account.
     *
     * <p>Runs unfiltered on purpose: the whole question is whether <em>someone else</em> used this
     * file, and the owner filter would hide exactly the row being looked for — turning the check
     * into a no-op that always passes.
     */
    private void rejectIfSeenUnderAnotherStudent(String sha256, Long studentId) {
        boolean seen = tenantScope.unfiltered(() -> uploads.findByFileSha256(sha256).stream()
                .anyMatch(existing -> !studentId.equals(existing.getOwnerId())));
        if (seen) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This transcript has already been used to join the leaderboard.");
        }
    }

    // ---------------------------------------------------------------------- step 2: target

    /**
     * What a candidate target is worth, so the student can see the ceiling move as they choose it.
     *
     * <p>Reads the baseline that was just captured rather than one supplied by the caller, so the
     * preview cannot be made to describe a season the student is not actually in.
     */
    public TargetPreviewResponse preview(BigDecimal target) {
        BigDecimal baseline = requireBaseline(studentService.getStudentID());
        return previewAgainst(baseline, target);
    }

    static TargetPreviewResponse previewAgainst(BigDecimal baseline, BigDecimal target) {
        double base = baseline.doubleValue();
        double goal = target == null ? base : target.doubleValue();
        ScoreMode mode = LeaderboardScoring.modeFor(base, goal);

        return new TargetPreviewResponse(
                baseline,
                BigDecimal.valueOf(goal).setScale(2, RoundingMode.HALF_UP),
                mode,
                LeaderboardScoring.ceiling(mode, base, goal),
                BigDecimal.valueOf(LeaderboardScoring.ambition(base, goal))
                        .setScale(3, RoundingMode.HALF_UP));
    }

    // ------------------------------------------------------------------------ step 3: join

    /**
     * Freezes the season and publishes an entry.
     *
     * <p>Insert-if-absent, never upsert. The {@code UNIQUE (student_id, season)} constraint is what
     * makes the baseline, target and mode write-once, and an upsert would mean that constraint is
     * never consulted — the structural guarantee would quietly become a convention.
     */
    @Transactional
    public void join(JoinLeaderboardRequest request) {
        Long studentId = studentService.getStudentID();
        Instant now = Instant.now();

        BigDecimal baselineGpa = requireBaseline(studentId);
        BigDecimal target = validateTarget(baselineGpa, request.targetGpa12());
        String handle = claimHandle(studentId, request.handle());

        // Rejoining after opting out: the frozen row survived the withdrawal, so this restores the
        // original starting line rather than handing out a fresh one.
        SeasonBaseline baseline = baselines.findByOwnerIdAndSeason(studentId, season)
                .orElseGet(() -> freeze(baselineGpa, target));

        LeaderboardProfile profile = profiles.findByOwnerId(studentId)
                .orElseGet(LeaderboardProfile::new);
        if (profile.getOptedInAt() == null) {
            profile.setOptedInAt(now);
        }
        // Re-recorded on every join: the rules are shown again at each gate, and the timestamp
        // should say when they were last agreed to rather than when they first were.
        profile.setRulesAcceptedAt(now);
        profile.setHandle(handle);
        if (request.avatar() != null) {
            profile.setAvatar(request.avatar());
        }
        profiles.save(profile);

        LeaderboardEntry entry = entries.findByStudentIdAndSeason(studentId, season)
                .orElseGet(LeaderboardEntry::new);
        entry.setStudentId(studentId);
        entry.setSeason(season);
        entry.setHandle(handle);
        entry.setAvatar(profile.getAvatar());
        entry.setStatus(EntryStatus.ACTIVE);
        entry.setScoreMode(baseline.getScoreMode());
        if (entry.getScore() == null) {
            entry.setScore(BigDecimal.ZERO);
            entry.setAmbition(BigDecimal.ZERO);
            entry.setBehaviorScore(BigDecimal.ONE);
            entry.setBehaviorFlags(0);
        }
        entries.save(entry);

        // Scored immediately, so joining lands on a real number instead of a zero that sits there
        // until the next scheduled run.
        ranking.rescoreNow(studentId);
        logger.info("Student {} joined the leaderboard for season {} in {} mode",
                studentId, season, baseline.getScoreMode());
    }

    private SeasonBaseline freeze(BigDecimal baselineGpa, BigDecimal target) {
        SeasonBaseline baseline = new SeasonBaseline();
        // Owner is stamped from the request context on persist — see UserOwnedEntity.
        baseline.setSeason(season);
        baseline.setBaselineGpa12(baselineGpa);
        baseline.setTargetGpa12(target);
        baseline.setScoreMode(LeaderboardScoring.modeFor(baselineGpa.doubleValue(), target.doubleValue()));
        return baselines.save(baseline);
    }

    // -------------------------------------------------------------------- leaving, renaming

    /**
     * Opting out mid-season.
     *
     * <p>Forfeits, never punishes: the entry stops being displayed and stops being scored, and
     * nothing is recorded against the student or carried into the next season. The row itself is
     * not deleted, which is what lets a rejoin restore the same frozen baseline.
     *
     * <p>The target they played with becomes their personal dashboard target, which they are then
     * free to edit. Without this the dashboard would jump back to whatever personal target they had
     * before joining.
     */
    @Transactional
    public void withdraw() {
        Long studentId = studentService.getStudentID();
        entries.findByStudentIdAndSeason(studentId, season).ifPresent(entry -> {
            if (entry.getStatus() == EntryStatus.ACTIVE) {
                baselines.findByOwnerIdAndSeason(studentId, season).ifPresent(frozen ->
                        studentService.setTargetGpa(
                                GpaScale.toFourPoint(frozen.getTargetGpa12()),
                                frozen.getTargetGpa12()));
            }
            entry.setStatus(EntryStatus.WITHDRAWN);
            entries.save(entry);
            logger.info("Student {} withdrew from the leaderboard for season {}", studentId, season);
        });
    }

    /**
     * Renames, once per season.
     *
     * <p>The limit is measured against this season's frozen baseline rather than against a stored
     * season boundary: a rename counts if it happened after the student joined this season. Because
     * {@code SeasonBaseline} is write-once and survives a withdrawal, cycling out and back in
     * cannot reset the allowance either.
     */
    @Transactional
    public void changeHandle(ChangeHandleRequest request) {
        Long studentId = studentService.getStudentID();
        LeaderboardProfile profile = profiles.findByOwnerId(studentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Join the leaderboard before choosing a handle."));

        if (!canChangeHandle(studentId, profile)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Handles can only be changed once per season.");
        }

        String handle = claimHandle(studentId, request.handle());
        profile.setHandle(handle);
        profile.setHandleChangedAt(Instant.now());
        profiles.save(profile);

        // The board denormalises the handle, so the published row has to be corrected too or the
        // rename only takes effect at the next scheduled run.
        entries.findByStudentIdAndSeason(studentId, season).ifPresent(entry -> {
            entry.setHandle(handle);
            entries.save(entry);
        });
    }

    /**
     * Changes the face. Not rate limited: an avatar carries no standing, so there is nothing a
     * student could shed by changing it.
     */
    @Transactional
    public void changeAvatar(ChangeAvatarRequest request) {
        Long studentId = studentService.getStudentID();
        LeaderboardProfile profile = profiles.findByOwnerId(studentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Join the leaderboard before choosing an avatar."));

        profile.setAvatar(request.avatar());
        profiles.save(profile);

        // Denormalised onto the board like the handle, so the change shows without waiting a night.
        entries.findByStudentIdAndSeason(studentId, season).ifPresent(entry -> {
            entry.setAvatar(request.avatar());
            entries.save(entry);
        });
    }

    /** Whether this student still has their one rename for the season. */
    public boolean canChangeHandle(Long studentId, LeaderboardProfile profile) {
        if (profile == null || profile.getHandleChangedAt() == null) {
            return true;
        }
        return baselines.findByOwnerIdAndSeason(studentId, season)
                .map(baseline -> baseline.getCreatedAt() != null
                        && profile.getHandleChangedAt().isBefore(baseline.getCreatedAt()))
                .orElse(true);
    }

    // ------------------------------------------------------------------------- validation

    private BigDecimal requireBaseline(Long studentId) {
        return baselines.findByOwnerIdAndSeason(studentId, season)
                .map(SeasonBaseline::getBaselineGpa12)
                .or(() -> uploads.findFirstByOwnerIdAndSeasonOrderByIdDesc(studentId, season)
                        .map(TranscriptUpload::getParsedGpa12))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                        "Upload your transcript before joining the leaderboard."));
    }

    /**
     * A target has to be a real goal on a real scale.
     *
     * <p>Note what is <em>not</em> rejected: a target less than a point above the baseline. That is
     * a legitimate choice — a student holding steady through a heavy term — and it selects
     * MAINTENANCE and its lower ceiling rather than failing. An earlier draft required a full point
     * of climb from everyone, which had a bug this feature surfaced: a student above 11.0 cannot
     * satisfy it at all, so taken literally it barred the strongest students from joining.
     */
    private BigDecimal validateTarget(BigDecimal baseline, BigDecimal target) {
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Set a target GPA to join.");
        }
        if (target.compareTo(BigDecimal.ZERO) < 0
                || target.compareTo(BigDecimal.valueOf(LeaderboardScoring.SCALE_MAX)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Target GPA has to be between 0 and 12.");
        }
        if (target.compareTo(baseline) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Target GPA cannot be below where you are starting.");
        }
        return target;
    }

    /**
     * Whether this student could claim this handle, without claiming it.
     *
     * <p>Exists so the handle field can answer while it is being typed instead of failing on
     * submit. It is the same pair of checks {@link #claimHandle} makes, which is the point: two
     * implementations would eventually disagree, and the one the student sees first would be the
     * wrong one.
     *
     * <p>The uniqueness read runs unfiltered for the same reason the transcript check does: under
     * the owner filter this student can only see their own profile, so every handle in the system
     * would look free.
     */
    @Transactional(readOnly = true)
    public HandleCheckResponse checkHandle(Long studentId, String requested) {
        String handle = requested == null ? null : requested.trim();

        String rejection = HandlePolicy.rejectionReason(handle);
        if (rejection != null) {
            return HandleCheckResponse.taken(rejection);
        }

        // Your own handle is not "taken" from where you are standing, or the settings screen would
        // tell a student their current name is unavailable the moment they opened the field.
        boolean heldByMe = profiles.findByOwnerId(studentId)
                .map(profile -> handle.equalsIgnoreCase(profile.getHandle()))
                .orElse(false);
        if (heldByMe) {
            return HandleCheckResponse.free();
        }

        if (tenantScope.unfiltered(() -> profiles.existsByHandleIgnoreCase(handle))) {
            return HandleCheckResponse.taken("That handle is taken.");
        }
        return HandleCheckResponse.free();
    }

    /**
     * Validates a handle and confirms nobody else holds it, or refuses.
     *
     * <p>Asks {@link #checkHandle} and turns its answer into a status, so the rules live in one
     * place. The functional unique index on {@code lower(handle)} is the backstop if two students
     * submit the same handle between this read and the write.
     */
    private String claimHandle(Long studentId, String requested) {
        String handle = requested == null ? null : requested.trim();
        HandleCheckResponse check = checkHandle(studentId, handle);
        if (check.available()) {
            return handle;
        }
        // A handle that is well-formed but spoken for is a conflict; anything else is malformed.
        HttpStatus status = HandlePolicy.rejectionReason(handle) == null
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
        throw new ResponseStatusException(status, check.reason());
    }

    // ------------------------------------------------------------------------- transcript

    private TranscriptBaselineResponse describe(BigDecimal baseline, boolean frozen) {
        double base = baseline.doubleValue();
        double headroom = LeaderboardScoring.headroom(base);
        boolean growthAvailable = headroom >= LeaderboardScoring.MIN_GROWTH_GAP;

        return new TranscriptBaselineResponse(
                baseline,
                BigDecimal.valueOf(headroom).setScale(2, RoundingMode.HALF_UP),
                growthAvailable,
                growthAvailable
                        ? BigDecimal.valueOf(base + LeaderboardScoring.MIN_GROWTH_GAP)
                                .setScale(2, RoundingMode.HALF_UP)
                        : null,
                frozen);
    }

    private BigDecimal parse(MultipartFile file) {
        try {
            return transcripts.parseGpa12(file);
        } catch (IOException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "That transcript could not be read. Upload the PDF exactly as Mosaic exports it.");
        }
    }

    private static byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attach your transcript.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That file could not be read.");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
