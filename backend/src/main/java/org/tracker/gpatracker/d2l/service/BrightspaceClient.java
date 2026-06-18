package org.tracker.gpatracker.d2l.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.tracker.gpatracker.d2l.config.D2LProperties;
import org.tracker.gpatracker.d2l.exceptions.D2LSessionExpiredException;
import org.tracker.gpatracker.d2l.model.D2LSession;

/**
 * Calls Brightspace Valence endpoints on behalf of a user, injecting the stored cookie jar, the
 * {@code X-Csrf-Token}, and a desktop User-Agent on every request.
 *
 * <p>Implements the spec's <em>reactive</em> refresh: on a 401 it calls {@link D2LSessionService#refresh}
 * once and retries before surfacing an error. Refresh is never done on a timer. Because {@code RestClient}
 * is synchronous this is a catch-retry wrapper rather than a {@code WebClient} exchange filter.
 */
@Service
public class BrightspaceClient {

    private static final Logger logger = LoggerFactory.getLogger(BrightspaceClient.class);

    private final D2LSessionService sessionService;
    private final D2LProperties properties;
    private final RestClient restClient;

    public BrightspaceClient(D2LSessionService sessionService, D2LProperties properties) {
        this.sessionService = sessionService;
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    /** {@code GET /d2l/api/lp/{version}/users/whoami} — proof the cookie+XSRF auth works. */
    public String whoami(Long userId) {
        return getJson(userId, "/d2l/api/lp/" + properties.getApiVersion() + "/users/whoami");
    }

    /** {@code GET /d2l/api/lp/{version}/enrollments/myenrollments/} — the user's Avenue enrollments. */
    public String myEnrollments(Long userId) {
        return getJson(userId, "/d2l/api/lp/" + properties.getApiVersion() + "/enrollments/myenrollments/");
    }

    /** Performs an authenticated GET, refreshing once on 401 and retrying. Returns the raw JSON body. */
    private String getJson(Long userId, String path) {
        D2LSession session = sessionService.require(userId);
        String body = attempt(session, path, true);
        if (body == REFRESH_SIGNAL) {
            logger.info("D2L 401 for user {} — refreshing and retrying once", userId);
            D2LSession refreshed = sessionService.refresh(userId);
            body = attempt(refreshed, path, false);
            if (body == REFRESH_SIGNAL) {
                throw new D2LSessionExpiredException("D2L returned 401 after refresh for user " + userId);
            }
        }
        return body;
    }

    /** Sentinel returned by {@link #attempt} to signal a 401 that should trigger a refresh. */
    private static final String REFRESH_SIGNAL = new String("__D2L_REFRESH__");

    private String attempt(D2LSession session, String path, boolean refreshable) {
        return restClient.get()
                .uri(properties.getBaseUrl() + path)
                .header(HttpHeaders.COOKIE, session.getCookieHeader())
                .header("X-Csrf-Token", session.getXsrfToken())
                .header(HttpHeaders.USER_AGENT, properties.getUserAgent())
                .header(HttpHeaders.ACCEPT, "application/json")
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 401 && refreshable) {
                        return REFRESH_SIGNAL;
                    }
                    if (status == 401 || status == 403) {
                        throw new D2LSessionExpiredException("D2L authorization failed (status " + status + ")");
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("D2L request to " + path + " failed: " + status);
                    }
                    return response.bodyTo(String.class);
                });
    }
}
