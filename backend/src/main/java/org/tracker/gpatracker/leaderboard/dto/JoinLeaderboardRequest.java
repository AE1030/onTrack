package org.tracker.gpatracker.leaderboard.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.tracker.gpatracker.leaderboard.model.LeaderboardAvatar;

import java.math.BigDecimal;

/**
 * The last step of the gate: a target, a name, and a deliberate yes.
 *
 * <p>The transcript is not part of this request — it was uploaded first, and its parsed GPA is
 * already recorded for the season. Splitting the two is what makes the ceiling preview possible:
 * the student cannot be shown what a target is worth until a baseline exists to measure it against.
 *
 * @param targetGpa12 mandatory. A target below {@code baseline + 1.0} is not rejected; it selects
 *                    MAINTENANCE and its lower ceiling, which is a legitimate choice for a student
 *                    holding steady through a heavy term rather than a validation failure.
 * @param handle      the name published on the board
 * @param acceptRules the board publishes a score derived from grades this app encrypts at rest,
 *                    so joining takes an explicit yes rather than a default
 * @param avatar      optional. Null keeps whatever face the profile already has, which on a first
 *                    join means one derived from the handle
 */
public record JoinLeaderboardRequest(

        @NotNull(message = "Set a target GPA to join")
        @DecimalMin(value = "0.0", message = "Target GPA cannot be negative")
        @DecimalMax(value = "12.0", message = "Target GPA cannot exceed 12.0")
        BigDecimal targetGpa12,

        @NotBlank(message = "Pick a handle for the board")
        @Size(min = 3, max = 24, message = "Handles are between 3 and 24 characters")
        String handle,

        @AssertTrue(message = "The leaderboard rules have to be accepted to join")
        boolean acceptRules,

        @Valid
        LeaderboardAvatar avatar) {

    public JoinLeaderboardRequest(BigDecimal targetGpa12, String handle, boolean acceptRules) {
        this(targetGpa12, handle, acceptRules, null);
    }
}
