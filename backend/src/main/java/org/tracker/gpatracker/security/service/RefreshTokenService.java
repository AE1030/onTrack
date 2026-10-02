package org.tracker.gpatracker.security.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.keygen.BytesKeyGenerator;
import org.springframework.security.crypto.keygen.KeyGenerators;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tracker.gpatracker.security.model.RefreshToken;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.repository.RefreshTokenRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <p>Every successful refresh revokes the presented token and issues a new one with the same
 * absolute expiry. Presenting an already-rotated token again is treated as a sign the token was
 * copied, and revokes every session the user has, except inside a short grace window: two browser
 * tabs sharing one stored token can legitimately race to refresh it, and the loser should just be
 * told no rather than signing the user out everywhere.
 */
@Service
public class RefreshTokenService {

    private static final Logger logger = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final BytesKeyGenerator TOKEN_GENERATOR = KeyGenerators.secureRandom(32);

    private final RefreshTokenRepository repository;
    private final Duration ttl;
    private final Duration reuseGrace;
    private final Clock clock;

    // Explicit because the class has a second, test-only constructor; with two, Spring will not
    // pick one on its own.
    @Autowired
    public RefreshTokenService(RefreshTokenRepository repository,
                               @Value("${jwt.refresh.expiry.days:90}") long ttlDays,
                               @Value("${jwt.refresh.reuse-grace.seconds:60}") long reuseGraceSeconds) {
        this(repository, Duration.ofDays(ttlDays), Duration.ofSeconds(reuseGraceSeconds), Clock.systemUTC());
    }

    RefreshTokenService(RefreshTokenRepository repository, Duration ttl, Duration reuseGrace, Clock clock) {
        this.repository = repository;
        this.ttl = ttl;
        this.reuseGrace = reuseGrace;
        this.clock = clock;
    }

    /** A freshly issued raw token and the user it belongs to. */
    public record Issued(String rawToken, Users user) {
    }

    /** Starts a new session for {@code user}. Returns the raw token; only its hash is stored. */
    @Transactional
    public String issue(Users user) {
        Instant now = clock.instant();
        repository.deleteExpiredForUser(user, now);
        return create(user, now.plus(ttl));
    }

    /**
     * Trades a valid refresh token for a new one. Empty when the token is unknown, expired, or
     * already used; the caller answers all of those the same way.
     *
     * <p>Returns rather than throws on the reuse path on purpose: throwing would roll back the
     * revocation this method just wrote.
     */
    @Transactional
    public Optional<Issued> rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<RefreshToken> found = repository.findByTokenHashForUpdate(hash(rawToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken token = found.get();

        if (token.isRevoked()) {
            if (token.getRevokedAt().plus(reuseGrace).isBefore(now) && !token.isExpired(now)) {
                logger.warn("Refresh token reuse detected for user id {}; revoking all sessions",
                        token.getUser().getId());
                repository.revokeAllForUser(token.getUser(), now);
            }
            return Optional.empty();
        }
        if (token.isExpired(now)) {
            return Optional.empty();
        }

        token.setRevokedAt(now);
        String next = create(token.getUser(), token.getExpiresAt());
        return Optional.of(new Issued(next, token.getUser()));
    }

    /** Signs one session out. Unknown tokens are ignored so logout never fails. */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByTokenHash(hash(rawToken))
                .filter(t -> !t.isRevoked())
                .ifPresent(t -> t.setRevokedAt(clock.instant()));
    }

    /** Signs every session for {@code user} out, e.g. after a password reset. */
    @Transactional
    public void revokeAll(Users user) {
        repository.revokeAllForUser(user, clock.instant());
    }

    private String create(Users user, Instant expiresAt) {
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(TOKEN_GENERATOR.generateKey());
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(hash(raw));
        token.setExpiresAt(expiresAt);
        repository.save(token);
        return raw;
    }

    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
