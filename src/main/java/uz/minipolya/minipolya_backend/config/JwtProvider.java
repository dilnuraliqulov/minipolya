package uz.minipolya.minipolya_backend.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uz.minipolya.minipolya_backend.entity.User;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies HS256 JWTs. Tokens carry the user id (subject) plus
 * {@code phone}, {@code isOwner} and {@code isAdmin} claims, plus {@code iatMs}: the issue
 * time in milliseconds, because the standard {@code iat} is whole seconds and too coarse
 * to tell a token from a password change made in the same second. Expiry comes from
 * {@code app.jwt.expiration-ms} (default 24h).
 */
@Slf4j
@Component
public class JwtProvider {

    private static final String ISSUED_AT_MS = "iatMs";

    private final SecretKey key;
    private final long expirationMs;

    public JwtProvider(AppProperties appProperties) {
        String secret = appProperties.getJwt().getSecret();
        // HS256 requires a key of at least 256 bits (32 bytes); a shorter
        // JWT_SECRET makes Keys.hmacShaKeyFor throw WeakKeyException at startup.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = appProperties.getJwt().getExpirationMs();
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("phone", user.getPhone())
                .claim("isOwner", user.isOwner())
                .claim("isAdmin", user.isAdmin())
                .issuedAt(Date.from(now))
                .claim(ISSUED_AT_MS, now.toEpochMilli())
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key)
                .compact();
    }

    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Invalid JWT: {}", ex.getMessage());
            return false;
        }
    }

    public UUID getUserId(String token) {
        return UUID.fromString(parse(token).getSubject());
    }

    public String getPhone(String token) {
        return parse(token).get("phone", String.class);
    }

    public boolean isOwner(String token) {
        return Boolean.TRUE.equals(parse(token).get("isOwner", Boolean.class));
    }

    public boolean isAdmin(String token) {
        return Boolean.TRUE.equals(parse(token).get("isAdmin", Boolean.class));
    }

    /**
     * When the token was issued, to the millisecond. A token from before {@code iatMs}
     * existed falls back to the whole-second {@code iat}.
     */
    public Instant getIssuedAt(String token) {
        Claims claims = parse(token);
        Long millis = claims.get(ISSUED_AT_MS, Long.class);
        return millis != null ? Instant.ofEpochMilli(millis) : claims.getIssuedAt().toInstant();
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
