package com.agreemint.security;

import com.agreemint.domain.OrgRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Authenticated user context carried through Spring Security.
 * Populated by {@link JwtAuthenticationFilter} from JWT claims.
 */
public record UserPrincipal(
        UUID userId,
        String email,
        UUID orgId,
        OrgRole role
) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (role == null) return List.of(new SimpleGrantedAuthority("ROLE_USER"));
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return null; // JWT-based, no password needed here
    }

    @Override
    public String getUsername() {
        return email;
    }
}
