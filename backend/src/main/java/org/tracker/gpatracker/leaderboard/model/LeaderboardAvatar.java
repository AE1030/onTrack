package org.tracker.gpatracker.leaderboard.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * A leaderboard face, as the five axis indexes the client draws it from.
 *
 * <p>The bounds mirror {@code AVATAR_AXES} in {@code frontend/src/leaderboard/avatar.ts}. Adding an
 * option on the client means raising the matching {@code @Max} here, or saves of the new option
 * are rejected.
 */
public record LeaderboardAvatar(
        @Min(value = 0, message = "Pick a valid hairstyle") @Max(value = 5, message = "Pick a valid hairstyle")
        int face,
        @Min(value = 0, message = "Pick a valid skin tone") @Max(value = 7, message = "Pick a valid skin tone")
        int skin,
        @Min(value = 0, message = "Pick a valid hair colour") @Max(value = 7, message = "Pick a valid hair colour")
        int hair,
        @Min(value = 0, message = "Pick a valid accessory") @Max(value = 3, message = "Pick a valid accessory")
        int gear,
        @Min(value = 0, message = "Pick a valid colour") @Max(value = 5, message = "Pick a valid colour")
        int accent) {
}
