package org.tracker.gpatracker.leaderboard.controller;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.tracker.gpatracker.leaderboard.dto.ChangeAvatarRequest;
import org.tracker.gpatracker.leaderboard.dto.ChangeHandleRequest;
import org.tracker.gpatracker.leaderboard.dto.HandleCheckResponse;
import org.tracker.gpatracker.leaderboard.dto.JoinLeaderboardRequest;
import org.tracker.gpatracker.leaderboard.dto.LeaderboardResponse;
import org.tracker.gpatracker.leaderboard.dto.LeaderboardStatusResponse;
import org.tracker.gpatracker.leaderboard.dto.TargetPreviewResponse;
import org.tracker.gpatracker.leaderboard.dto.TranscriptBaselineResponse;
import org.tracker.gpatracker.accounts.service.StudentService;
import org.tracker.gpatracker.leaderboard.service.LeaderboardOnboardingService;
import org.tracker.gpatracker.leaderboard.service.LeaderboardService;

import java.math.BigDecimal;

/**
 * The leaderboard, from joining it to reading it.
 *
 * <p>Every route is scoped to the caller through {@code UserContext} and none of them accepts a
 * student id. All require authentication, which is the default in {@code SecurityConfig} — the
 * board is public in the sense that every student sees the same rows, not in the sense that it is
 * readable without an account.
 *
 * <p>The join flow is three calls rather than one because the middle one cannot exist otherwise:
 * a target's ceiling is meaningless until a baseline has been captured to measure it against.
 */
@RestController
@RequestMapping("/api/leaderboard")
public class LeaderboardController {

    private static final Logger logger = LoggerFactory.getLogger(LeaderboardController.class);

    private final LeaderboardService leaderboardService;
    private final LeaderboardOnboardingService onboardingService;
    private final StudentService studentService;

    public LeaderboardController(LeaderboardService leaderboardService,
                                 LeaderboardOnboardingService onboardingService,
                                 StudentService studentService) {
        this.leaderboardService = leaderboardService;
        this.onboardingService = onboardingService;
        this.studentService = studentService;
    }

    /** The board for the current season, plus the caller's own row wherever it sits. */
    @GetMapping
    public ResponseEntity<LeaderboardResponse> board(@RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(leaderboardService.board(limit));
    }

    /** Whether the caller has joined, and what they have left to spend. */
    @GetMapping("/status")
    public ResponseEntity<LeaderboardStatusResponse> status() {
        return ResponseEntity.ok(leaderboardService.status());
    }

    /** Step 1: establish the season's baseline from a transcript. */
    @PostMapping("/transcript")
    public ResponseEntity<TranscriptBaselineResponse> transcript(@RequestPart("file") MultipartFile file) {
        logger.info("POST /api/leaderboard/transcript");
        return ResponseEntity.ok(onboardingService.submitTranscript(file));
    }

    /** Step 2: what a candidate target would be worth, recomputed as the student moves it. */
    @GetMapping("/preview")
    public ResponseEntity<TargetPreviewResponse> preview(@RequestParam BigDecimal target) {
        return ResponseEntity.ok(onboardingService.preview(target));
    }

    /** Step 3: freeze the baseline, target and mode, and publish an entry. */
    @PostMapping("/join")
    public ResponseEntity<LeaderboardStatusResponse> join(@Valid @RequestBody JoinLeaderboardRequest request) {
        logger.info("POST /api/leaderboard/join");
        onboardingService.join(request);
        return ResponseEntity.ok(leaderboardService.status());
    }

    /** Opting out. Forfeits this season's standing and records nothing against the student. */
    @DeleteMapping("/entry")
    public ResponseEntity<Void> withdraw() {
        onboardingService.withdraw();
        return ResponseEntity.noContent().build();
    }

    /**
     * Whether a handle is free, for the field to answer while it is being typed.
     *
     * <p>Read-only and never claims anything: two students can both be told a handle is available
     * and the loser finds out at submit, where the unique index settles it. Reserving on a
     * keystroke would let anyone empty the namespace by typing.
     */
    @GetMapping("/handle/check")
    public ResponseEntity<HandleCheckResponse> checkHandle(@RequestParam String handle) {
        return ResponseEntity.ok(
                onboardingService.checkHandle(studentService.getStudentID(), handle));
    }

    /** Renaming, once per season. */
    @PostMapping("/handle")
    public ResponseEntity<LeaderboardStatusResponse> changeHandle(
            @Valid @RequestBody ChangeHandleRequest request) {
        onboardingService.changeHandle(request);
        return ResponseEntity.ok(leaderboardService.status());
    }

    /** Changing the face. As often as the student likes. */
    @PostMapping("/avatar")
    public ResponseEntity<LeaderboardStatusResponse> changeAvatar(
            @Valid @RequestBody ChangeAvatarRequest request) {
        onboardingService.changeAvatar(request);
        return ResponseEntity.ok(leaderboardService.status());
    }
}
