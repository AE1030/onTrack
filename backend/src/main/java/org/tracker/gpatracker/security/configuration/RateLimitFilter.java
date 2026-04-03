package org.tracker.gpatracker.security.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.tracker.gpatracker.security.model.UserPrincipal;

import java.io.IOException;
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

    private final ConcurrentHashMap<String, RateBucket> buckets = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RateLimitFilter() {
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

    private String extractIp(HttpServletRequest request) {
        return request.getRemoteAddr();
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
