package com.agreemint.billing;

import com.agreemint.admin.domain.OrgQuota;
import com.agreemint.admin.repository.OrgQuotaRepository;
import com.agreemint.config.PlanLimitsProperties;
import com.agreemint.domain.OrgPlan;
import com.agreemint.repository.OrganizationRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves what an organisation is currently allowed to do.
 *
 * <p>Single place that combines the org's plan with any staff-set override in
 * {@code org_quotas}. Resolution order, most specific first:
 * <ol>
 *   <li>explicit per-org override in {@code org_quotas}</li>
 *   <li>the plan's limit from {@link PlanLimitsProperties}</li>
 *   <li>null — caller falls back to the system default</li>
 * </ol>
 *
 * <p>This sits on the hot path for every API-key request, so results are cached
 * briefly. The cache is per-instance and best-effort: a plan change becomes
 * visible within {@link #TTL}, and {@link #invalidate} clears it immediately on
 * the node that processed the change.
 */
@Service
public class OrgEntitlementService {

    /** Short enough that a webhook-driven change is felt almost immediately. */
    private static final Duration TTL = Duration.ofSeconds(60);

    /**
     * What an org may currently do.
     *
     * @param apiDailyMax null means "use the system default"
     * @param pdfDailyMax null means "unlimited / use the system default"
     * @param apiRpmOverride null means "use the per-key value"
     */
    public record Entitlement(
            OrgPlan plan,
            Integer apiDailyMax,
            Integer pdfDailyMax,
            Integer apiRpmOverride,
            boolean frozen,
            String frozenReason
    ) {}

    private record CacheEntry(Entitlement value, Instant expiresAt) {}

    private final OrganizationRepository orgRepo;
    private final OrgQuotaRepository quotaRepo;
    private final PlanLimitsProperties planLimits;
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    public OrgEntitlementService(OrganizationRepository orgRepo,
                                  OrgQuotaRepository quotaRepo,
                                  PlanLimitsProperties planLimits) {
        this.orgRepo = orgRepo;
        this.quotaRepo = quotaRepo;
        this.planLimits = planLimits;
    }

    public Entitlement resolve(UUID orgId) {
        if (orgId == null) return unlimited();

        CacheEntry cached = cache.get(orgId);
        Instant now = Instant.now();
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.value();
        }

        Entitlement resolved = load(orgId);
        cache.put(orgId, new CacheEntry(resolved, now.plus(TTL)));
        return resolved;
    }

    /** Drop the cached entitlement so the next read reflects a change at once. */
    public void invalidate(UUID orgId) {
        if (orgId != null) cache.remove(orgId);
    }

    private Entitlement load(UUID orgId) {
        OrgPlan plan = orgRepo.findById(orgId)
                .map(org -> org.getPlan() == null ? OrgPlan.FREE : org.getPlan())
                .orElse(OrgPlan.FREE);

        OrgQuota quota = quotaRepo.findById(orgId).orElse(null);

        Integer apiDaily = firstNonNull(
                quota == null ? null : quota.getApiDailyCap(),
                planLimits.apiDailyMaxFor(plan));
        Integer pdfDaily = firstNonNull(
                quota == null ? null : quota.getPdfDailyCap(),
                planLimits.pdfDailyMaxFor(plan));
        Integer rpm = quota == null ? null : quota.getApiRpmOverride();

        return new Entitlement(
                plan,
                apiDaily,
                pdfDaily,
                rpm,
                quota != null && quota.isFrozen(),
                quota == null ? null : quota.getFrozenReason());
    }

    private static Entitlement unlimited() {
        return new Entitlement(OrgPlan.FREE, null, null, null, false, null);
    }

    private static Integer firstNonNull(Integer a, Integer b) {
        return a != null ? a : b;
    }
}
