package com.agreemint.security;

import com.agreemint.admin.service.ImpersonationSessionService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Cover for "API keys win" actually being true.
 *
 * <p>{@code SecurityConfig} asserted it on the strength of filter order alone.
 * Order only decides who runs <em>first</em>: {@code JwtAuthenticationFilter}
 * then called {@code setAuthentication} unconditionally and replaced the
 * API-key principal, dropping every {@code SCOPE_} authority with it — so a
 * request carrying both an {@code X-Api-Key} and a stale browser JWT got a 403
 * on {@code /api/v1/*}. Running first was precisely what made API keys lose.
 */
class ApiKeyPrecedenceTest {

    private JwtService jwt;
    private JwtAuthenticationFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        jwt = mock(JwtService.class);
        filter = new JwtAuthenticationFilter(jwt, mock(ImpersonationSessionService.class));
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** What ApiKeyAuthenticationFilter installs: a principal carrying scopes. */
    private Authentication apiKeyAuth() {
        UserPrincipal principal = new UserPrincipal(
                UUID.randomUUID(), "api-key@org", UUID.randomUUID(), null, Set.of("documents:write"));
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("SCOPE_documents:write")));
    }

    private void validJwtOnTheRequest() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(UUID.randomUUID().toString());
        when(claims.get("email", String.class)).thenReturn("someone@example.com");
        when(jwt.extractClaimsOrNull(anyString())).thenReturn(claims);
        when(request.getHeader("Authorization")).thenReturn("Bearer a.valid.token");
    }

    @Test
    void anExistingApiKeyAuthenticationIsNotReplaced() throws Exception {
        Authentication apiKey = apiKeyAuth();
        SecurityContextHolder.getContext().setAuthentication(apiKey);
        validJwtOnTheRequest();

        filter.doFilter(request, response, chain);

        assertSame(apiKey, SecurityContextHolder.getContext().getAuthentication(),
                "the API-key principal must survive a request that also carries a JWT");
        verify(chain).doFilter(request, response);
    }

    @Test
    void theKeysScopesSurvive() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(apiKeyAuth());
        validJwtOnTheRequest();

        filter.doFilter(request, response, chain);

        // The 403 came from here: a JWT principal carries no SCOPE_ authorities,
        // so replacing the API-key one silently stripped them.
        assertTrue(SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                        .anyMatch(a -> a.getAuthority().equals("SCOPE_documents:write")),
                "overwriting the principal is what dropped the key's scopes");
    }

    @Test
    void theJwtStillAuthenticatesWhenNothingRanBefore() throws Exception {
        validJwtOnTheRequest();

        filter.doFilter(request, response, chain);

        // The guard must not break ordinary browser requests, which are the
        // overwhelming majority and have an empty context at this point.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth, "a JWT-only request must still authenticate");
        assertInstanceOf(UserPrincipal.class, auth.getPrincipal());
        assertEquals("someone@example.com", ((UserPrincipal) auth.getPrincipal()).email());
    }

    @Test
    void aRequestWithNoCredentialsIsUntouched() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
    }
}
