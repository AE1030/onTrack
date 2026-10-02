package org.tracker.gpatracker.security.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tracker.gpatracker.security.model.RefreshToken;
import org.tracker.gpatracker.security.model.Users;
import org.tracker.gpatracker.security.repository.RefreshTokenRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final Map<String, RefreshToken> byHash = new HashMap<>();
    private RefreshTokenRepository repository;
    private MutableClock clock;
    private RefreshTokenService service;
    private Users user;

    @BeforeEach
    void setUp() {
        repository = mock(RefreshTokenRepository.class);
        when(repository.save(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken t = inv.getArgument(0);
            byHash.put(t.getTokenHash(), t);
            return t;
        });
        when(repository.findByTokenHashForUpdate(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(byHash.get(inv.<String>getArgument(0))));
        when(repository.findByTokenHash(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(byHash.get(inv.<String>getArgument(0))));

        clock = new MutableClock(T0);
        service = new RefreshTokenService(repository, Duration.ofDays(90), Duration.ofSeconds(60), clock);
        user = new Users();
        user.setId(7L);
    }

    @Test
    @DisplayName("only the hash is stored")
    void storesHashNotRawToken() {
        String raw = service.issue(user);
        assertThat(byHash).containsOnlyKeys(RefreshTokenService.hash(raw));
        assertThat(byHash.values().iterator().next().getTokenHash()).isNotEqualTo(raw);
    }

    @Test
    @DisplayName("rotation revokes the old token and keeps the original expiry")
    void rotateIssuesNewTokenWithSameExpiry() {
        String first = service.issue(user);
        clock.advance(Duration.ofDays(10));

        Optional<RefreshTokenService.Issued> issued = service.rotate(first);

        assertThat(issued).isPresent();
        assertThat(issued.get().user()).isSameAs(user);
        assertThat(byHash.get(RefreshTokenService.hash(first)).isRevoked()).isTrue();
        RefreshToken next = byHash.get(RefreshTokenService.hash(issued.get().rawToken()));
        assertThat(next.isRevoked()).isFalse();
        assertThat(next.getExpiresAt()).isEqualTo(T0.plus(Duration.ofDays(90)));
    }

    @Test
    @DisplayName("an expired token cannot be rotated")
    void expiredTokenRejected() {
        String raw = service.issue(user);
        clock.advance(Duration.ofDays(91));
        assertThat(service.rotate(raw)).isEmpty();
    }

    @Test
    @DisplayName("unknown and blank tokens are rejected")
    void unknownTokenRejected() {
        assertThat(service.rotate("nope")).isEmpty();
        assertThat(service.rotate("")).isEmpty();
        assertThat(service.rotate(null)).isEmpty();
    }

    @Test
    @DisplayName("reuse inside the grace window is refused without revoking other sessions")
    void reuseInsideGraceIsQuiet() {
        String raw = service.issue(user);
        service.rotate(raw);
        clock.advance(Duration.ofSeconds(30));

        assertThat(service.rotate(raw)).isEmpty();
        verify(repository, never()).revokeAllForUser(any(), any());
    }

    @Test
    @DisplayName("reuse after the grace window revokes every session for the user")
    void reuseAfterGraceRevokesAll() {
        String raw = service.issue(user);
        service.rotate(raw);
        clock.advance(Duration.ofMinutes(5));

        assertThat(service.rotate(raw)).isEmpty();
        verify(repository).revokeAllForUser(eq(user), any());
    }

    @Test
    @DisplayName("a logged-out token cannot be rotated")
    void revokedTokenRejected() {
        String raw = service.issue(user);
        service.revoke(raw);
        assertThat(service.rotate(raw)).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
