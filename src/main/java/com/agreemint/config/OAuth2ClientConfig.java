package com.agreemint.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.ArrayList;
import java.util.List;

/**
 * Programmatically registers OAuth2 client registrations based on
 * {@link OAuthProperties}. This replaces Spring Boot's auto-configured
 * {@code ClientRegistrationRepository} so we can skip providers whose
 * credentials are not set without causing a startup failure.
 */
@Configuration
public class OAuth2ClientConfig {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(OAuthProperties props) {
        List<ClientRegistration> registrations = new ArrayList<>();

        if (props.isGoogleReady()) {
            registrations.add(
                ClientRegistration.withRegistrationId("google")
                    .clientId(props.getGoogleClientId())
                    .clientSecret(props.getGoogleClientSecret())
                    .scope("email", "profile")
                    .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                    .tokenUri("https://oauth2.googleapis.com/token")
                    .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                    .userNameAttributeName("sub")
                    .clientName("Google")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/api/oauth2/callback/{registrationId}")
                    .build()
            );
        }

        if (props.isGithubReady()) {
            registrations.add(
                ClientRegistration.withRegistrationId("github")
                    .clientId(props.getGithubClientId())
                    .clientSecret(props.getGithubClientSecret())
                    .scope("user:email", "read:user")
                    .authorizationUri("https://github.com/login/oauth/authorize")
                    .tokenUri("https://github.com/login/oauth/access_token")
                    .userInfoUri("https://api.github.com/user")
                    .userNameAttributeName("id")
                    .clientName("GitHub")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/api/oauth2/callback/{registrationId}")
                    .build()
            );
        }

        if (registrations.isEmpty()) {
            // Return a no-op repository — no OAuth providers available
            return registrationId -> null;
        }

        return new InMemoryClientRegistrationRepository(registrations);
    }
}
