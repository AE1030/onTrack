package org.tracker.gpatracker.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.tracker.gpatracker.leaderboard.service.LeaderboardRankingService;
import org.tracker.gpatracker.leaderboard.service.LeaderboardRankingService.RecomputeResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobTriggerControllerTest {

    private final LeaderboardRankingService ranking = mock(LeaderboardRankingService.class);

    @Test
    @DisplayName("the right token runs the recompute and reports what it did")
    void rightTokenRuns() {
        when(ranking.recompute()).thenReturn(new RecomputeResult("Winter 2026", 3, 4, 12, false));
        var controller = new JobTriggerController(ranking, "secret");

        var response = controller.leaderboardRecompute("secret");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new RecomputeResult("Winter 2026", 3, 4, 12, false));
    }

    @Test
    @DisplayName("a missing or wrong token is refused without running anything")
    void wrongTokenIsRefused() {
        var controller = new JobTriggerController(ranking, "secret");

        assertThat(controller.leaderboardRecompute(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.leaderboardRecompute("guess").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(ranking, never()).recompute();
    }

    @Test
    @DisplayName("with no secret configured, even an empty token is refused")
    void unconfiguredFailsClosed() {
        var controller = new JobTriggerController(ranking, "");

        assertThat(controller.leaderboardRecompute("").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(ranking, never()).recompute();
    }

    @Test
    @DisplayName("a run already in progress answers 202 so Cloud Scheduler does not retry")
    void overlappingRunIsAccepted() {
        when(ranking.recompute()).thenReturn(new RecomputeResult("Winter 2026", 0, 0, 0, true));
        var controller = new JobTriggerController(ranking, "secret");

        assertThat(controller.leaderboardRecompute("secret").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }
}
