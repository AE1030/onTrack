package org.tracker.gpatracker.leaderboard.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.tracker.gpatracker.leaderboard.model.LeaderboardAvatar;

/** A new face. Unlike a handle, not rate limited: it carries no standing. */
public record ChangeAvatarRequest(

        @NotNull(message = "Pick an avatar")
        @Valid
        LeaderboardAvatar avatar) {
}
