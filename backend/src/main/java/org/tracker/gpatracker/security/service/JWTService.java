package org.tracker.gpatracker.security.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class JWTService {

    private final String secretkey;
    private final long expiryMs;

    public JWTService(@Value("${JWT_SECRET_KEY}") String secretkey,
                      @Value("${jwt.expiry.ms:3600000}") long expiryMs) {
        this.secretkey = secretkey;
        this.expiryMs = expiryMs;
    }

    /** Claim carrying the {@code Users} id — the auth identity. */
    public static final String CLAIM_USER_ID = "uid";

    /** Claim carrying the {@code Student} id — the tenant that owns domain rows. */
    public static final String CLAIM_STUDENT_ID = "sid";

    /**
     * Mint a token carrying the caller's identity as <em>signed</em> claims.
     *
     * <p>The tenant id must never come from anything the client controls. Putting it in the
     * signed payload means the server can trust it without a per-request database lookup —
     * previously {@code StudentService.getStudentID()} re-queried on every call, several times
     * within a single request.
     *
     * @param studentId may be null only for accounts with no {@code Student} row yet; login
     *                  requires a verified email and verification creates that row, so in
     *                  practice this is always populated at issue time.
     */
    public String generateToken(String username, Long userId, Long studentId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_USER_ID, userId);
        if (studentId != null) {
            claims.put(CLAIM_STUDENT_ID, studentId);
        }
        return Jwts.builder()
                .claims()
                .add(claims)
                .subject(username)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiryMs))
                .and()
                .signWith(getKey())
                .compact();

    }

    /** The {@code Users} id from the signed payload, or null on a token minted before this claim existed. */
    public Long extractUserId(String token) {
        return extractIdClaim(token, CLAIM_USER_ID);
    }

    /** The {@code Student} id from the signed payload, or null on a token minted before this claim existed. */
    public Long extractStudentId(String token) {
        return extractIdClaim(token, CLAIM_STUDENT_ID);
    }

    /**
     * Reads a numeric claim defensively. JSON has one number type, so a value that fits in an
     * int deserialises as Integer while a larger one deserialises as Long — reading either as
     * {@code Long.class} directly would throw for small ids.
     */
    private Long extractIdClaim(String token, String name) {
        Object value = extractClaim(token, claims -> claims.get(name));
        return (value instanceof Number number) ? number.longValue() : null;
    }

    private SecretKey getKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secretkey);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String extractUserName(String token) {
        // extract the username from jwt token
        return extractClaim(token, Claims::getSubject);
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimResolver) {
        final Claims claims = extractAllClaims(token);
        return claimResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token, UserDetails userDetails) {
        final String userName = extractUserName(token);
        return (userName.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

}
