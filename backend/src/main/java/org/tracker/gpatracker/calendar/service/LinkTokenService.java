package org.tracker.gpatracker.calendar.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import org.tracker.gpatracker.calendar.config.LinkTokenProperties;
import org.tracker.gpatracker.calendar.dto.LinkTokenPayload;
import org.tracker.gpatracker.calendar.model.CalendarProvider;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class LinkTokenService {

    private final LinkTokenProperties properties;
    private final SecretKey key;

    public LinkTokenService(LinkTokenProperties properties) {
        this.properties = properties;
        byte[] secretBytes = Base64.getDecoder().decode(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    public String createStateToken(Long userId, CalendarProvider provider) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(properties.getTtlSeconds());
        return Jwts.builder()
                .issuer(properties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim("uid", userId)
                .claim("provider", provider.name())
                .signWith(key)
                .compact();
    }

    public LinkTokenPayload parseStateToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Long userId = claims.get("uid", Long.class);
        String provider = claims.get("provider", String.class);
        if (userId == null || provider == null) {
            throw new IllegalArgumentException("Invalid state token");
        }
        return new LinkTokenPayload(userId, CalendarProvider.valueOf(provider));
    }
}
