package org.tracker.gpatracker.d2l.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.tracker.gpatracker.d2l.config.D2LProperties;
import org.tracker.gpatracker.d2l.exceptions.D2LSessionExpiredException;
import org.tracker.gpatracker.d2l.model.D2LSession;
import org.tracker.gpatracker.d2l.repository.D2LSessionRepository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-user replacement for the spec's in-memory {@code SessionStore} singleton.
 *
 * <p>Holds each user's harvested cookie jar + XSRF token (persisted, encrypted) and performs the
 * browser-free HTTP refresh: it replays an authenticated request carrying the long-lived session
 * cookie, parses the {@code Set-Cookie} response, and merges the refreshed short-lived token back into
 * the stored jar. No browser is ever opened server-side; when the long-lived session is dead it throws
 * {@link D2LSessionExpiredException} so the client re-launches the WebView login.
 */
@Service
public class D2LSessionService {

    private static final Logger logger = LoggerFactory.getLogger(D2LSessionService.class);

    private final D2LSessionRepository repository;
    private final D2LProperties properties;
    private final RestClient restClient;

    public D2LSessionService(D2LSessionRepository repository, D2LProperties properties) {
        this.repository = repository;
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    /** Upserts the harvested session for a user (called after a device WebView login). */
    public void save(Long userId, String cookieHeader, String xsrfToken) {
        if (cookieHeader == null || cookieHeader.isBlank() || xsrfToken == null || xsrfToken.isBlank()) {
            throw new IllegalArgumentException("cookieHeader and xsrfToken are required");
        }
        Instant now = Instant.now();
        D2LSession session = repository.findByUserId(userId).orElseGet(() -> {
            D2LSession fresh = new D2LSession();
            fresh.setUserId(userId);
            fresh.setCreatedAt(now);
            return fresh;
        });
        session.setCookieHeader(cookieHeader.trim());
        session.setXsrfToken(xsrfToken.trim());
        session.setUpdatedAt(now);
        repository.save(session);
        logger.info("Stored D2L session for user {}", userId);
    }

    public boolean hasSession(Long userId) {
        return repository.existsByUserId(userId);
    }

    @Transactional
    public void delete(Long userId) {
        repository.deleteByUserId(userId);
    }

    /** Returns the user's session or throws if none exists. */
    public D2LSession require(Long userId) {
        return repository.findByUserId(userId)
                .orElseThrow(() -> new D2LSessionExpiredException("No D2L session for user " + userId));
    }

    public String getCookieHeader(Long userId) {
        return require(userId).getCookieHeader();
    }

    public String getXsrfToken(Long userId) {
        return require(userId).getXsrfToken();
    }

    /**
     * Browser-free refresh of the short-lived auth token. Replays {@code d2l.refreshPath} with the
     * stored cookie jar, merges any refreshed cookies from {@code Set-Cookie}, and persists the result.
     *
     * @throws D2LSessionExpiredException if the long-lived session is dead (401/403)
     */
    public D2LSession refresh(Long userId) {
        D2LSession session = require(userId);
        logger.info("Refreshing D2L session for user {}", userId);

        List<String> setCookies = restClient.get()
                .uri(properties.getBaseUrl() + properties.getRefreshPath())
                .header(HttpHeaders.COOKIE, session.getCookieHeader())
                .header("X-Csrf-Token", session.getXsrfToken())
                .header(HttpHeaders.USER_AGENT, properties.getUserAgent())
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 401 || status == 403) {
                        throw new D2LSessionExpiredException(
                                "D2L long-lived session is dead (status " + status + ")");
                    }
                    return response.getHeaders().get(HttpHeaders.SET_COOKIE);
                });

        String merged = mergeCookies(session.getCookieHeader(), setCookies);
        session.setCookieHeader(merged);
        session.setLastRefreshAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        return repository.save(session);
    }

    /**
     * Merges {@code Set-Cookie} response headers into an existing {@code k=v; k=v} cookie jar.
     * Only the leading {@code name=value} pair of each Set-Cookie is kept (attributes like Path/HttpOnly
     * are dropped); later values overwrite earlier ones.
     */
    static String mergeCookies(String existingHeader, List<String> setCookies) {
        Map<String, String> jar = new LinkedHashMap<>();
        if (existingHeader != null) {
            for (String pair : existingHeader.split(";")) {
                addPair(jar, pair);
            }
        }
        if (setCookies != null) {
            for (String setCookie : setCookies) {
                if (setCookie == null || setCookie.isBlank()) continue;
                // first segment before ';' is the name=value; the rest are attributes
                String first = setCookie.split(";", 2)[0];
                addPair(jar, first);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static void addPair(Map<String, String> jar, String pair) {
        if (pair == null) return;
        String trimmed = pair.trim();
        int eq = trimmed.indexOf('=');
        if (eq <= 0) return;
        String name = trimmed.substring(0, eq).trim();
        String value = trimmed.substring(eq + 1).trim();
        if (!name.isEmpty()) {
            jar.put(name, value);
        }
    }
}
