package org.tracker.gpatracker.leaderboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A rename, allowed once per season — see {@code LeaderboardOnboardingService.changeHandle}. */
public record ChangeHandleRequest(

        @NotBlank(message = "Pick a handle for the board")
        @Size(min = 3, max = 24, message = "Handles are between 3 and 24 characters")
        String handle) {
}
