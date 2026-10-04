package org.tracker.gpatracker.security.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.tracker.gpatracker.security.model.UserPrincipal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> AUTH_ENDPOINTS = Set.of(
            "/login", "/register", "/verify", "/verify/resend",
            "/forgot-password", "/reset-password"
    );

    private static final int AUTH_LIMIT = 10;       // requests per window
    private static final int GENERAL_LIMIT = 60;    // requests per window
    private static final long WINDOW_MS = 60_000;   // 1 minute window
    private static final long STALE_MS = 600_000;   // 10 minutes

    /** Set by the Cloudflare Worker with the real visitor address. Trusted only with the secret. */
    static final String CLIENT_IP_HEADER = "X-Client-IP";
    /** Shared secret the Worker adds so the backend knows the request really came through it. */
    static final String ORIGIN_SECRET_HEADER = "X-Origin-Secret";

    private final ConcurrentHashMap<String, RateBucket> buckets = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final byte[] originSecret;

    public RateLimitFilter(@Value("${app.origin.shared-secret:}") String originSecret) {
        this.originSecret = originSecret.getBytes(StandardCharsets.UTF_8);
        ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limit-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(this::cleanupStale, 5, 5, TimeUnit.MINUTES);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String key = resolveKey(request);
        int limit = isAuthEndpoint(request.getRequestURI()) ? AUTH_LIMIT : GENERAL_LIMIT;

        RateBucket bucket = buckets.computeIfAbsent(key, k -> new RateBucket(limit));

        if (!bucket.tryConsume(limit)) {
            response.setStatus(429);
            response.setContentType("application/json");
            objectMapper.writeValue(response.getOutputStream(),
                    Map.of("error", "Too many requests. Please try again later.", "status", 429));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveKey(HttpServletRequest request) {
        String ip = extractIp(request);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return principal.getId() + ":" + ip;
        }
        return ip;
    }

    /**
     * The address that identifies a visitor.
     *
     * <p>Behind Cloudflare and Cloud Run, {@code getRemoteAddr()} is always a proxy, so every
     * logged-out visitor would share one bucket. The Worker passes the real address in
     * {@value #CLIENT_IP_HEADER}, but a header is only as honest as whoever sent it: anyone who calls
     * the run.app address directly could set it to dodge the limit. So it is believed only when the
     * request also carries {@value #ORIGIN_SECRET_HEADER} matching the configured secret. With no
     * secret configured the header is ignored, so a deploy that forgets the variable falls back to
     * the old behaviour instead of trusting anyone.
     */
    String extractIp(HttpServletRequest request) {
        if (originSecret.length > 0 && hasValidOriginSecret(request)) {
            String forwarded = request.getHeader(CLIENT_IP_HEADER);
            if (isPlausibleAddress(forwarded)) {
                return forwarded.trim();
            }
        }
        return request.getRemoteAddr();
    }

    private boolean hasValidOriginSecret(HttpServletRequest request) {
        String presented = request.getHeader(ORIGIN_SECRET_HEADER);
        return presented != null
                && MessageDigest.isEqual(originSecret, presented.getBytes(StandardCharsets.UTF_8));
    }

    /** Characters an IPv4 or IPv6 address can contain, so a bad header cannot bloat the bucket map. */
    private static boolean isPlausibleAddress(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim();
        return !v.isEmpty() && v.length() <= 45 && v.matches("[0-9a-fA-F:.]+");
    }

    private boolean isAuthEndpoint(String uri) {
        return AUTH_ENDPOINTS.contains(uri);
    }

    private void cleanupStale() {
        long now = System.currentTimeMillis();
        buckets.entrySet().removeIf(e -> now - e.getValue().lastAccess > STALE_MS);
    }

    private static class RateBucket {
        private final AtomicInteger tokens;
        private volatile long windowStart;
        private volatile long lastAccess;

        RateBucket(int limit) {
            this.tokens = new AtomicInteger(limit);
            this.windowStart = System.currentTimeMillis();
            this.lastAccess = this.windowStart;
        }

        synchronized boolean tryConsume(int limit) {
            long now = System.currentTimeMillis();
            lastAccess = now;

            if (now - windowStart >= WINDOW_MS) {
                tokens.set(limit);
                windowStart = now;
            }

            return tokens.getAndDecrement() > 0;
        }
    }
}
