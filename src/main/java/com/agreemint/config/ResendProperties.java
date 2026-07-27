package com.agreemint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for Resend, the transactional email provider. EmailService
 * POSTs rendered Thymeleaf HTML to the Resend HTTP API over HTTPS — there is
 * no SMTP involved. With an empty {@link #apiKey} (e.g. local dev / preview
 * without credentials) EmailService logs the email and skips the send rather
 * than failing, preserving the old "mail not configured" behaviour.
 */
@Component
@ConfigurationProperties(prefix = "agreemint.email.resend")
public class ResendProperties {

    private String apiKey = "";
    private String baseUrl = "https://api.resend.com";

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
}
