package com.agreemint.security;

import com.agreemint.config.OAuthProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
// Required so @PreAuthorize("hasAuthority('SCOPE_…')") on the v1 controller actually enforces.
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final ApiKeyAuthenticationFilter apiKeyFilter;
    private final OAuthProperties oauthProps;

    @Value("${agreemint.cors.origins:http://localhost:5173}")
    private String corsOrigins;

    public SecurityConfig(
            JwtAuthenticationFilter jwtFilter,
            ApiKeyAuthenticationFilter apiKeyFilter,
            OAuthProperties oauthProps) {
        this.jwtFilter = jwtFilter;
        this.apiKeyFilter = apiKeyFilter;
        this.oauthProps = oauthProps;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(corsOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // WebSocket endpoint (auth handled by STOMP interceptor)
                .requestMatchers("/ws", "/ws/**").permitAll()
                // Public endpoints
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/oauth2/**").permitAll()
                // Swagger UI & OpenAPI spec
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                // Static resources and health
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/error").permitAll()
                // Public developer API — authenticated via X-Api-Key filter; scope
                // checks are enforced per-endpoint via @PreAuthorize.
                .requestMatchers("/api/v1/**").authenticated()
                // Internal admin portal — gated on the ROLE_STAFF authority that
                // UserPrincipal emits when the JWT's `isStaff` claim is true.
                // Non-staff requests come back as 403 before any controller runs.
                .requestMatchers("/api/admin/**").hasAuthority("ROLE_STAFF")
                // Everything else under /api requires auth
                .requestMatchers("/api/**").authenticated()
                // Let non-API requests through (frontend SPA assets, etc.)
                .anyRequest().permitAll()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, authEx) -> {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"Unauthorized\"}");
                })
            )
            // Order: API-key filter first (handles X-Api-Key), then JWT filter. A
            // request carrying both headers is handled by whichever runs first —
            // API keys win by design.
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        // Only enable OAuth2 login if at least one provider is configured
        if (oauthProps.isAnyEnabled()) {
            http.oauth2Login(oauth -> oauth
                .authorizationEndpoint(ep -> ep.baseUri("/api/oauth2/authorize"))
                .redirectionEndpoint(ep -> ep.baseUri("/api/oauth2/callback/*"))
            );
        }

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
