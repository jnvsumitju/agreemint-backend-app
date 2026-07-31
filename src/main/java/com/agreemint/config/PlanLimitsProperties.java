package com.agreemint.config;

import com.agreemint.domain.OrgPlan;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Per-plan resource ceilings.
 *
 * <p><strong>Every value defaults to null, meaning "no plan-specific limit".</strong>
 * That is deliberate: until this was added, no org had a plan-derived cap at
 * all, and every org fell back to {@code agreemint.ratelimit.org-daily-max}.
 * Shipping opinionated numbers here would silently throttle existing customers
 * the moment billing deployed. Set them explicitly once the commercial plans
 * are decided.
 *
 * <p>Resolution order, applied in {@code OrgEntitlementService}:
 * per-org override in {@code org_quotas} → plan limit here → system default.
 */
@Component
@ConfigurationProperties(prefix = "agreemint.plans")
public class PlanLimitsProperties {

    private Integer freeApiDailyMax;
    private Integer starterApiDailyMax;
    private Integer proApiDailyMax;
    private Integer enterpriseApiDailyMax;

    private Integer freePdfDailyMax;
    private Integer starterPdfDailyMax;
    private Integer proPdfDailyMax;
    private Integer enterprisePdfDailyMax;

    /** Daily API request cap for a plan, or null to fall back. */
    public Integer apiDailyMaxFor(OrgPlan plan) {
        if (plan == null) return null;
        return switch (plan) {
            case FREE -> freeApiDailyMax;
            case STARTER -> starterApiDailyMax;
            case PRO -> proApiDailyMax;
            case ENTERPRISE -> enterpriseApiDailyMax;
        };
    }

    /** Daily PDF generation cap for a plan, or null to fall back. */
    public Integer pdfDailyMaxFor(OrgPlan plan) {
        if (plan == null) return null;
        return switch (plan) {
            case FREE -> freePdfDailyMax;
            case STARTER -> starterPdfDailyMax;
            case PRO -> proPdfDailyMax;
            case ENTERPRISE -> enterprisePdfDailyMax;
        };
    }

    public Integer getStarterApiDailyMax() { return starterApiDailyMax; }
    public void setStarterApiDailyMax(Integer v) { this.starterApiDailyMax = v; }
    public Integer getStarterPdfDailyMax() { return starterPdfDailyMax; }
    public void setStarterPdfDailyMax(Integer v) { this.starterPdfDailyMax = v; }
    public Integer getFreeApiDailyMax() { return freeApiDailyMax; }
    public void setFreeApiDailyMax(Integer v) { this.freeApiDailyMax = v; }
    public Integer getProApiDailyMax() { return proApiDailyMax; }
    public void setProApiDailyMax(Integer v) { this.proApiDailyMax = v; }
    public Integer getEnterpriseApiDailyMax() { return enterpriseApiDailyMax; }
    public void setEnterpriseApiDailyMax(Integer v) { this.enterpriseApiDailyMax = v; }
    public Integer getFreePdfDailyMax() { return freePdfDailyMax; }
    public void setFreePdfDailyMax(Integer v) { this.freePdfDailyMax = v; }
    public Integer getProPdfDailyMax() { return proPdfDailyMax; }
    public void setProPdfDailyMax(Integer v) { this.proPdfDailyMax = v; }
    public Integer getEnterprisePdfDailyMax() { return enterprisePdfDailyMax; }
    public void setEnterprisePdfDailyMax(Integer v) { this.enterprisePdfDailyMax = v; }
}
