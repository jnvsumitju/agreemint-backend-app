package com.agreemint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the DeepSeek-backed AI template generator. The frontend
 * never sees any of these — the editor calls the backend, which proxies the
 * request and streams the response back. With an empty {@link #apiKey} the
 * AI endpoint returns 503 so dev / preview environments without credentials
 * fail fast rather than silently 401-ing on the upstream call.
 */
@Component
@ConfigurationProperties(prefix = "agreemint.ai.deepseek")
public class DeepSeekProperties {

    private String apiKey = "";
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-v4-pro";
    private int timeoutSeconds = 300;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
