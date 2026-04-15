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
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenExpiry)))
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
