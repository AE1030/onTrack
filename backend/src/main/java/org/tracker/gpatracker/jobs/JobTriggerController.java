package org.tracker.gpatracker.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tracker.gpatracker.leaderboard.service.LeaderboardRankingService;
import org.tracker.gpatracker.leaderboard.service.LeaderboardRankingService.RecomputeResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * HTTP triggers for scheduled work, for a caller outside the app (Cloud Scheduler).
 *
 * <p>Exists because the in-process {@code @Scheduled} crons cannot be relied on in production.
 * Cloud Run runs with no minimum instances, so at the scheduled minute there may be nothing alive to
 * fire them, and an instance between requests has its CPU throttled. A request both starts an
 * instance and keeps CPU allocated until the response is written, so each trigger does its work
 * synchronously and only answers when it is finished.
 *
 * <p>Not behind the JWT: there is no user. Authorised instead by a shared secret in the
 * {@value #TOKEN_HEADER} header, compared in constant time. With no secret configured every call is
 * refused, so a deploy that forgets the variable fails closed rather than leaving the trigger open.
 */
@RestController
@RequestMapping("/internal/jobs")
public class JobTriggerController {

    public static final String TOKEN_HEADER = "X-Jobs-Token";

    private static final Logger logger = LoggerFactory.getLogger(JobTriggerController.class);

    private final LeaderboardRankingService rankingService;
    private final byte[] expectedToken;

    public JobTriggerController(LeaderboardRankingService rankingService,
                                @Value("${jobs.trigger.token:}") String token) {
        this.rankingService = rankingService;
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    /** Rescores the leaderboard, behaviour flags included. */
    @PostMapping("/leaderboard-recompute")
    public ResponseEntity<?> leaderboardRecompute(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        if (!authorised(token)) {
            logger.warn("POST /internal/jobs/leaderboard-recompute refused: missing or wrong token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authorised", "status", 401));
        }

        RecomputeResult result = rankingService.recompute();
        if (result.skipped()) {
            // Cloud Scheduler retries on non-2xx. A run already in progress is the job being done,
            // not failing, so it must not trigger a retry.
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
        }
        return ResponseEntity.ok(result);
    }

    private boolean authorised(String token) {
        if (expectedToken.length == 0 || token == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8));
    }
}
