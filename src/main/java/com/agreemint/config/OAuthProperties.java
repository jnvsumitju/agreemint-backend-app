package com.agreemint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Controls which OAuth2 providers are enabled and their credentials.
 * Set via environment variables:
 *   OAUTH_GOOGLE_ENABLED=true  GOOGLE_CLIENT_ID=xxx  GOOGLE_CLIENT_SECRET=yyy
 *   OAUTH_GITHUB_ENABLED=true  GITHUB_CLIENT_ID=xxx  GITHUB_CLIENT_SECRET=yyy
 * Both default to false (OAuth disabled).
 */
@Component
@ConfigurationProperties(prefix = "agreemint.oauth")
public class OAuthProperties {

    private boolean googleEnabled = false;
    private String googleClientId = "";
    private String googleClientSecret = "";

    private boolean githubEnabled = false;
    private String githubClientId = "";
    private String githubClientSecret = "";

    public boolean isGoogleEnabled() { return googleEnabled; }
    public void setGoogleEnabled(boolean googleEnabled) { this.googleEnabled = googleEnabled; }

    public String getGoogleClientId() { return googleClientId; }
    public void setGoogleClientId(String googleClientId) { this.googleClientId = googleClientId; }

    public String getGoogleClientSecret() { return googleClientSecret; }
    public void setGoogleClientSecret(String googleClientSecret) { this.googleClientSecret = googleClientSecret; }

    public boolean isGithubEnabled() { return githubEnabled; }
    public void setGithubEnabled(boolean githubEnabled) { this.githubEnabled = githubEnabled; }

    public String getGithubClientId() { return githubClientId; }
    public void setGithubClientId(String githubClientId) { this.githubClientId = githubClientId; }

    public String getGithubClientSecret() { return githubClientSecret; }
    public void setGithubClientSecret(String githubClientSecret) { this.githubClientSecret = githubClientSecret; }

    /** True if at least one provider is enabled with a non-empty client-id. */
    public boolean isAnyEnabled() {
        return (googleEnabled && googleClientId != null && !googleClientId.isBlank())
            || (githubEnabled && githubClientId != null && !githubClientId.isBlank());
    }

    public boolean isGoogleReady() {
        return googleEnabled && googleClientId != null && !googleClientId.isBlank();
    }

    public boolean isGithubReady() {
        return githubEnabled && githubClientId != null && !githubClientId.isBlank();
    }
}
