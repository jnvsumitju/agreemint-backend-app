package com.agreemint.security;

import com.agreemint.domain.OrgRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Authenticated user context carried through Spring Security.
 *
 * <p>Populated by one of two filters:
 * <ul>
 *     <li>{@link JwtAuthenticationFilter} — browser / JWT path; {@link #role}
 *         is set, {@link #scopes} is empty.</li>
 *     <li>{@link ApiKeyAuthenticationFilter} — machine / {@code X-Api-Key}
 *         path; {@link #role} is null, {@link #scopes} carries the key's
 *         scope wire names.</li>
 * </ul>
 */
public record UserPrincipal(
        UUID userId,
        String email,
        UUID orgId,
        OrgRole role,
        Set<String> scopes
) implements UserDetails {

    /** Legacy 4-arg constructor kept so JWT callers don't need to construct an empty scope set. */
    public UserPrincipal(UUID userId, String email, UUID orgId, OrgRole role) {
        this(userId, email, orgId, role, Set.of());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> auths = new ArrayList<>();
        if (role != null) {
            auths.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        } else if ((scopes == null || scopes.isEmpty())) {
            // Neither role nor scopes (legacy X-Org-Id override without a
            // subsequent per-request lookup) — fall back to a generic marker.
            auths.add(new SimpleGrantedAuthority("ROLE_USER"));
        }
        if (scopes != null) {
            for (String s : scopes) auths.add(new SimpleGrantedAuthority("SCOPE_" + s));
        }
        return auths;
    }

    @Override
    public String getPassword() {
        return null; // JWT / API-key based, no password needed here
    }

    @Override
    public String getUsername() {
        return email;
    }
}
