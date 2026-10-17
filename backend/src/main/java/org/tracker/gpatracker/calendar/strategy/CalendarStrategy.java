package org.tracker.gpatracker.calendar.strategy;

import org.tracker.gpatracker.calendar.dto.OAuthTokens;
import org.tracker.gpatracker.calendar.model.CalendarEvents.InternalCalendarEvent;
import org.tracker.gpatracker.calendar.model.CalendarProvider;

/**
 * Everything that differs between calendar providers, and nothing that doesn't.
 *
 * <p>The diff against the last export, snapshot bookkeeping, token expiry checks and iterating a
 * student's accounts all live in {@code CalendarSyncService} and are written once. An
 * implementation only translates our event into the provider's request shape and makes the HTTP
 * call. If a method here starts needing a repository, that logic probably belongs in the service.
 *
 * <p>Implementations are Spring beans; {@link CalendarStrategyRegistry} collects them by
 * {@link #provider()}.
 */
public interface CalendarStrategy {

    /** The provider this strategy handles. Exactly one strategy may claim each provider. */
    CalendarProvider provider();

    /**
     * Exchange a refresh token for a fresh access token.
     *
     * <p>The returned refresh token may differ from the one passed in (Microsoft rotates them on
     * every use), so callers must persist it when non-null.
     *
     * @throws org.tracker.gpatracker.calendar.exception.CalendarAuthorizationRevokedException only
     *         when the provider rejects the refresh token itself; the caller deletes the account.
     *         Any other failure must surface as some other exception.
     */
    OAuthTokens refreshAccessToken(String refreshToken);

    /**
     * Create the event in the account's default calendar.
     *
     * @return the provider's id for the new event, stored in the export so it can be deleted later
     */
    String createEvent(String accessToken, InternalCalendarEvent event);

    void updateEvent(String accessToken, String externalEventId, InternalCalendarEvent event);

    void deleteEvent(String accessToken, String externalEventId);

    /**
     * Withdraw onTrack's access to the account, called on disconnect after any event removal.
     * Best effort: the caller logs a failure and deletes the account regardless.
     *
     * <p>A no-op by default, for providers with no per-app revocation endpoint (Microsoft's only
     * option signs the user out of every session, which is far more than a disconnect means).
     */
    default void revoke(String refreshToken) {
    }
}
