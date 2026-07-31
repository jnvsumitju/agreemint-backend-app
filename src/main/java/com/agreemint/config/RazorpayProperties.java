package com.agreemint.config;

import com.agreemint.domain.BillingPeriod;
import com.agreemint.domain.OrgPlan;
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

    /**
     * Razorpay Plan ids — one per purchasable (plan × billing period) pair.
     * Create these in the Razorpay dashboard; the amount and interval live
     * there, so a price change is a new Plan plus a new id here, not a deploy.
     */
    private String planStarterMonthly = "";
    private String planStarterYearly = "";
    private String planProMonthly = "";
    private String planProYearly = "";

    /**
     * How many billing cycles to authorise. Razorpay requires a finite count
     * and enforces a hard ceiling per period — exceeding it fails subscription
     * creation with "Exceeds the maximum total_count allowed for the given
     * period and interval".
     *
     * <p>Their maximums at interval 1: <strong>100 for monthly</strong> and
     * <strong>10 for yearly</strong>. Both defaults sit at that ceiling, which
     * is ~8 years of monthly billing or 10 of yearly — effectively "until
     * cancelled" for any real customer. A subscription that runs to term ends
     * as {@code completed}, which downgrades the org to FREE.
     */
    private int totalCountMonthly = 100;
    private int totalCountYearly = 10;

    public boolean isConfigured() {
        return !keyId.isBlank() && !keySecret.isBlank();
    }

    public boolean isWebhookConfigured() {
        return !webhookSecret.isBlank();
    }

    /**
     * Razorpay Plan id for a (plan, period) pair, or blank when that
     * combination is not sold — which is how the console decides whether to
     * offer the button at all.
     */
    public String planIdFor(OrgPlan plan, BillingPeriod period) {
        boolean yearly = period == BillingPeriod.YEARLY;
        return switch (plan) {
            case STARTER -> yearly ? planStarterYearly : planStarterMonthly;
            case PRO -> yearly ? planProYearly : planProMonthly;
            // FREE needs no subscription; ENTERPRISE is contract-led and set
            // by staff rather than bought through checkout.
            case FREE, ENTERPRISE -> "";
        };
    }

    /** Whether a given plan can be purchased on any cycle right now. */
    public boolean isPurchasable(OrgPlan plan) {
        return !planIdFor(plan, BillingPeriod.MONTHLY).isBlank()
                || !planIdFor(plan, BillingPeriod.YEARLY).isBlank();
    }

    /** Razorpay's hard ceilings at interval 1. Exceeding these is a 400. */
    private static final int MAX_TOTAL_COUNT_MONTHLY = 100;
    private static final int MAX_TOTAL_COUNT_YEARLY = 10;

    /**
     * Cycles to authorise, clamped to Razorpay's ceiling.
     *
     * <p>Clamping rather than validating at startup on purpose: an
     * over-large value should not turn into a failed checkout for a paying
     * customer, and the difference between 100 and 120 months is meaningless
     * in practice.
     */
    public int totalCountFor(BillingPeriod period) {
        return period == BillingPeriod.YEARLY
                ? Math.min(Math.max(1, totalCountYearly), MAX_TOTAL_COUNT_YEARLY)
                : Math.min(Math.max(1, totalCountMonthly), MAX_TOTAL_COUNT_MONTHLY);
    }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getKeySecret() { return keySecret; }
    public void setKeySecret(String keySecret) { this.keySecret = keySecret; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getPlanStarterMonthly() { return planStarterMonthly; }
    public void setPlanStarterMonthly(String v) { this.planStarterMonthly = v; }
    public String getPlanStarterYearly() { return planStarterYearly; }
    public void setPlanStarterYearly(String v) { this.planStarterYearly = v; }
    public String getPlanProMonthly() { return planProMonthly; }
    public void setPlanProMonthly(String planProMonthly) { this.planProMonthly = planProMonthly; }
    public String getPlanProYearly() { return planProYearly; }
    public void setPlanProYearly(String planProYearly) { this.planProYearly = planProYearly; }
    public int getTotalCountMonthly() { return totalCountMonthly; }
    public void setTotalCountMonthly(int v) { this.totalCountMonthly = v; }
    public int getTotalCountYearly() { return totalCountYearly; }
    public void setTotalCountYearly(int v) { this.totalCountYearly = v; }
}
