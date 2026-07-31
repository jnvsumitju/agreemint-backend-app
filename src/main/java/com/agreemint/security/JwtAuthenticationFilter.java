package com.agreemint.security;

import com.agreemint.admin.service.ImpersonationSessionService;
import com.agreemint.domain.OrgRole;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Extracts a Bearer JWT from the Authorization header, validates it,
 * and sets a {@link UserPrincipal} into the Spring Security context.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final ImpersonationSessionService impersonationSessions;

    public JwtAuthenticationFilter(JwtService jwtService,
            ImpersonationSessionService impersonationSessions) {
        this.jwtService = jwtService;
        this.impersonationSessions = impersonationSessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // ApiKeyAuthenticationFilter runs before this one and may already have
        // authenticated the request. Never replace it. The API-key principal
        // carries the key's SCOPE_ authorities and a JWT principal carries
        // none, so overwriting silently stripped every scope and turned a
        // request sent with both headers into a 403 on /api/v1/*.
        //
        // SecurityConfig's comment claimed "API keys win by design" on the
        // strength of filter order alone. Order decides who runs first; without
        // this guard, running first is precisely what made API keys lose.
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && existing.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        Claims claims = jwtService.extractClaimsOrNull(token);
        if (claims == null || "refresh".equals(claims.get("type"))) {
            // Invalid token or refresh token used as access token
            chain.doFilter(request, response);
            return;
        }

        UUID userId = UUID.fromString(claims.getSubject());
        String email = claims.get("email", String.class);
        String orgIdStr = claims.get("orgId", String.class);
        String roleStr = claims.get("role", String.class);
        Boolean staffClaim = claims.get("isStaff", Boolean.class);
        boolean staff = Boolean.TRUE.equals(staffClaim);

        // Impersonation context. Previously both claims were written by
        // JwtService and read by nothing, so every action in an impersonated
        // session was recorded against the target user with no trace of the
        // operator.
        String impersonatedByStr = claims.get("impersonatedBy", String.class);
        String impersonationSid = claims.get("impersonationSid", String.class);
        UUID impersonatedBy = impersonatedByStr != null ? UUID.fromString(impersonatedByStr) : null;

        if (impersonatedBy != null && !impersonationSessions.isLive(impersonationSid)) {
            // Revoked or expired from the registry — the JWT alone is no longer
            // enough. Fails closed if Redis is unreachable.
            writeUnauthorized(response, "Impersonation session has ended");
            return;
        }

        UUID orgId = orgIdStr != null ? UUID.fromString(orgIdStr) : null;
        OrgRole role = roleStr != null ? OrgRole.valueOf(roleStr) : null;

        // Allow override via X-Org-Id header (for org switching without re-issuing JWT).
        //
        // Never for an impersonation token. That header is unvalidated client
        // input, so honouring it here would undo the whole point of asking the
        // operator which workspace to open: they pick one, the token is minted
        // and audited against it, and then a single header lets the session act
        // in any other workspace the target belongs to. Pinning to the claim
        // keeps the session inside the org the audit trail names.
        String orgOverride = request.getHeader("X-Org-Id");
        if (impersonatedBy == null && orgOverride != null && !orgOverride.isBlank()) {
            try {
                orgId = UUID.fromString(orgOverride.trim());
                // Role will be resolved by OrgAuthorizationService per-request when needed
                role = null;
            } catch (IllegalArgumentException ignored) {
                // Keep JWT org
            }
        }

        UserPrincipal principal = new UserPrincipal(
                userId, email, orgId, role, java.util.Set.of(), staff,
                impersonatedBy, impersonationSid);
        var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(request, response);
    }

    private static void writeUnauthorized(jakarta.servlet.http.HttpServletResponse resp, String reason)
            throws java.io.IOException {
        resp.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json");
        resp.getWriter().write("{\"error\":\"" + reason.replace("\"", "\\\"") + "\"}");
    }
}
