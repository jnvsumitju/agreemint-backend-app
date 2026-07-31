package com.agreemint.security;

import com.agreemint.domain.OrgRole;
import com.agreemint.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final Duration accessTokenExpiry;
    private final Duration refreshTokenExpiry;

    public JwtService(
            @Value("${agreemint.jwt.secret}") String secret,
            @Value("${agreemint.jwt.access-token-expiry-minutes:15}") long accessMinutes,
            @Value("${agreemint.jwt.refresh-token-expiry-days:7}") long refreshDays
    ) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiry = Duration.ofMinutes(accessMinutes);
        this.refreshTokenExpiry = Duration.ofDays(refreshDays);
    }

    /** Generate a short-lived access token with user + org context. */
    public String generateAccessToken(User user, UUID orgId, OrgRole role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("name", user.getName())
                .claim("orgId", orgId != null ? orgId.toString() : null)
                .claim("role", role != null ? role.name() : null)
                // `isStaff` gates the admin portal + /api/admin/* routes. Always
                // included (true or false) so the frontend can decide what to
                // render on login without an extra round-trip.
                .claim("isStaff", user.isStaff())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenExpiry)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Short-lived impersonation token — issued by a staff user to stand in as a
     * target user for debugging / support. Carries an `impersonatedBy` claim so
     * the audit log can always attribute actions to the real operator, and a
     * tight {@code ttl} (typically ≤ 30 min) passed by the caller.
     */
    public String generateImpersonationToken(
            User target,
            UUID targetOrgId,
            OrgRole targetRole,
            UUID impersonatedBy,
            Duration ttl, String sessionId) {
        Instant now = Instant.now();
        Duration effective = ttl != null ? ttl : Duration.ofMinutes(15);
        return Jwts.builder()
                .subject(target.getId().toString())
                .claim("email", target.getEmail())
                .claim("name", target.getName())
                .claim("orgId", targetOrgId != null ? targetOrgId.toString() : null)
                .claim("role", targetRole != null ? targetRole.name() : null)
                .claim("isStaff", false)
                .claim("impersonationSid", sessionId)
                .claim("impersonatedBy", impersonatedBy != null ? impersonatedBy.toString() : null)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(effective)))
                .signWith(signingKey)
                .compact();
    }

    /** Generate a long-lived refresh token (only contains user ID). */
    public String generateRefreshToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim("type", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTokenExpiry)))
                .signWith(signingKey)
                .compact();
    }

    /** Parse and validate a JWT, returning its claims. Throws on invalid/expired. */
    public Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Try to extract claims; returns null if the token is invalid or expired. */
    public Claims extractClaimsOrNull(String token) {
        try {
            return validateToken(token);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public Duration getRefreshTokenExpiry() {
        return refreshTokenExpiry;
    }
}
