package com.agreemint.config;

import com.agreemint.domain.BillingPeriod;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Razorpay credentials and plan mapping.
 *
 * <p>{@link #keyId} is public — the frontend needs it to open Checkout — but
 * {@link #keySecret} and {@link #webhookSecret} must never leave the server.
 *
 * <p>Amounts and billing intervals live in the Razorpay dashboard, not here:
 * a Razorpay Plan already carries its price, currency and period, so we only
 * store the plan ids. That also means a price change is a dashboard action plus
 * a new plan id, not a deploy.
 *
 * <p>With an empty {@link #keyId} the billing endpoints return 503 rather than
 * failing deeper in, so a dev environment without credentials degrades cleanly.
 */
@Component
@ConfigurationProperties(prefix = "agreemint.razorpay")
public class RazorpayProperties {

    private String keyId = "";
    private String keySecret = "";
    /** Secret configured against the webhook in the Razorpay dashboard. */
    private String webhookSecret = "";
    private String baseUrl = "https://api.razorpay.com/v1";

    /** Razorpay Plan ids, one per purchasable billing period. */
    private String planProMonthly = "";
    private String planProYearly = "";

    /**
     * How many billing cycles to authorise. Razorpay requires a finite count;
     * 120 months / 10 years is effectively "until cancelled" while staying
     * inside their limits.
     */
    private int totalCountMonthly = 120;
    private int totalCountYearly = 10;

    public boolean isConfigured() {
        return !keyId.isBlank() && !keySecret.isBlank();
    }

    public boolean isWebhookConfigured() {
        return !webhookSecret.isBlank();
    }

    /** Plan id for a billing period, or blank if that period is not sold. */
    public String planIdFor(BillingPeriod period) {
        return period == BillingPeriod.YEARLY ? planProYearly : planProMonthly;
    }

    public int totalCountFor(BillingPeriod period) {
        return period == BillingPeriod.YEARLY ? totalCountYearly : totalCountMonthly;
    }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getKeySecret() { return keySecret; }
    public void setKeySecret(String keySecret) { this.keySecret = keySecret; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getPlanProMonthly() { return planProMonthly; }
    public void setPlanProMonthly(String planProMonthly) { this.planProMonthly = planProMonthly; }
    public String getPlanProYearly() { return planProYearly; }
    public void setPlanProYearly(String planProYearly) { this.planProYearly = planProYearly; }
    public int getTotalCountMonthly() { return totalCountMonthly; }
    public void setTotalCountMonthly(int v) { this.totalCountMonthly = v; }
    public int getTotalCountYearly() { return totalCountYearly; }
    public void setTotalCountYearly(int v) { this.totalCountYearly = v; }
}
