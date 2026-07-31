package com.agreemint.admin.api;

import com.agreemint.billing.OrgEntitlementService;
import com.agreemint.billing.PdfQuotaService;
import com.agreemint.admin.api.dto.AdminDtos;
import com.agreemint.admin.domain.OrgQuota;
import com.agreemint.admin.repository.OrgQuotaRepository;
import com.agreemint.config.RateLimitConfig;
import com.agreemint.repository.OrganizationRepository;
import com.agreemint.security.UserPrincipal;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-org quota and freeze controls.
 *
 * <p><strong>What these fields actually do</strong> — stated precisely because
 * the admin portal repeats it to the operator, and because an earlier version
 * of this Javadoc overstated all three:
 *
 * <ul>
 *   <li>{@code apiRpmOverride} and {@code apiDailyCap} are enforced in
 *       {@code ApiKeyAuthenticationFilter} — they bound <em>API-key</em>
 *       traffic under {@code /api/v1/*} only. Neither limits the web console.</li>
 *   <li>{@code pdfDailyCap} is enforced in {@code PdfQuotaService}, charged
 *       once per persisted document. Editor previews are not charged.</li>
 *   <li>{@code frozen} is checked in {@code ApiKeyAuthenticationFilter} and
 *       returns 402 to API-key callers. It does <em>not</em> lock the org out
 *       of the web app — a frozen org's members can still sign in and work.</li>
 * </ul>
 *
 * <p>Values left NULL mean "inherit", but not uniformly.
 * {@code apiDailyCap} and {@code pdfDailyCap} fall back to the plan's limit in
 * {@code PlanLimitsProperties}, then to {@code RateLimitConfig}'s system
 * default for API traffic or to uncapped for documents. {@code apiRpmOverride}
 * has no plan tier at all — {@code PlanLimitsProperties} exposes no rpm — so a
 * NULL there falls straight through to each key's own {@code rate_limit_rpm}.
 * {@code GET} returns both the raw override and the resolved effective values,
 * so the portal can show what is really in force rather than a blank field.
 *
 * <p>Reads go through {@code OrgEntitlementService}, which caches for 60
 * seconds — a change here is felt within a minute, not instantly.
 * {@link #upsert} invalidates that cache on the node that served the write.
 */
@Tag(name = "Admin · Quotas")
@RestController
@RequestMapping("/api/admin/quotas")
public class AdminQuotaController {

    private final OrgQuotaRepository quotaRepo;
    private final OrganizationRepository orgRepo;
    private final OrgEntitlementService entitlements;
    private final PdfQuotaService pdfQuota;
    private final RateLimitConfig rateLimitConfig;

    public AdminQuotaController(OrgQuotaRepository quotaRepo, OrganizationRepository orgRepo,
            OrgEntitlementService entitlements, PdfQuotaService pdfQuota,
            RateLimitConfig rateLimitConfig) {
        this.quotaRepo = quotaRepo;
        this.orgRepo = orgRepo;
        this.entitlements = entitlements;
        this.pdfQuota = pdfQuota;
        this.rateLimitConfig = rateLimitConfig;
    }

    @GetMapping("/{orgId}")
    public ResponseEntity<AdminDtos.OrgQuotaView> get(@PathVariable UUID orgId) {
        if (orgRepo.findById(orgId).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(view(orgId, quotaRepo.findById(orgId).orElse(null)));
    }

    @PutMapping("/{orgId}")
    public ResponseEntity<AdminDtos.OrgQuotaView> upsert(
            @PathVariable UUID orgId,
            @RequestBody AdminDtos.OrgQuotaRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (orgRepo.findById(orgId).isEmpty()) return ResponseEntity.notFound().build();
        OrgQuota q = quotaRepo.findById(orgId).orElseGet(() -> {
            OrgQuota n = new OrgQuota();
            n.setOrgId(orgId);
            return n;
        });
        q.setApiRpmOverride(req.apiRpmOverride());
        q.setApiDailyCap(req.apiDailyCap());
        q.setPdfDailyCap(req.pdfDailyCap());
        if (req.frozen() != null) q.setFrozen(req.frozen());
        q.setFrozenReason(req.frozenReason());
        q.setUpdatedAt(Instant.now());
        q.setUpdatedBy(principal.userId());
        quotaRepo.save(q);

        // Must happen before the effective values are recomputed below, or the
        // response would echo the stale cached entitlement back to the operator
        // who just changed it.
        entitlements.invalidate(orgId);
        return ResponseEntity.ok(view(orgId, q));
    }

    private AdminDtos.OrgQuotaView view(UUID orgId, OrgQuota q) {
        OrgEntitlementService.Entitlement e = entitlements.resolve(orgId);
        return new AdminDtos.OrgQuotaView(
                toDto(q),
                e.plan() == null ? "FREE" : e.plan().name(),
                e.apiDailyMax(),
                e.pdfDailyMax(),
                rateLimitConfig.getOrgDailyMax(),
                pdfQuota.remainingToday(orgId));
    }

    private static AdminDtos.OrgQuotaSummary toDto(OrgQuota q) {
        if (q == null) return new AdminDtos.OrgQuotaSummary(null, null, null, false, null);
        return new AdminDtos.OrgQuotaSummary(
                q.getApiRpmOverride(), q.getApiDailyCap(), q.getPdfDailyCap(),
                q.isFrozen(), q.getFrozenReason());
    }
}
