package com.agreemint.security;

import com.agreemint.domain.OrgRole;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

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

        UUID orgId = orgIdStr != null ? UUID.fromString(orgIdStr) : null;
        OrgRole role = roleStr != null ? OrgRole.valueOf(roleStr) : null;

        // Allow override via X-Org-Id header (for org switching without re-issuing JWT)
        String orgOverride = request.getHeader("X-Org-Id");
        if (orgOverride != null && !orgOverride.isBlank()) {
            try {
                orgId = UUID.fromString(orgOverride.trim());
                // Role will be resolved by OrgAuthorizationService per-request when needed
                role = null;
            } catch (IllegalArgumentException ignored) {
                // Keep JWT org
            }
        }

        UserPrincipal principal = new UserPrincipal(userId, email, orgId, role, java.util.Set.of(), staff);
        var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        chain.doFilter(request, response);
    }
}
