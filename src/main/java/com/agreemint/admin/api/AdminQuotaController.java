package com.agreemint.admin.api;

import com.agreemint.admin.api.dto.AdminDtos;
import com.agreemint.admin.domain.OrgQuota;
import com.agreemint.admin.repository.OrgQuotaRepository;
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
 * Per-org quota / freeze controls. The rate limiter reads org_quotas on
 * each request; fields left NULL mean "fall back to the system default in
 * {@code RateLimitConfig}". Flipping `frozen` is the nuclear option — it
 * short-circuits every API call for the org.
 */
@Tag(name = "Admin · Quotas")
@RestController
@RequestMapping("/api/admin/quotas")
public class AdminQuotaController {

    private final OrgQuotaRepository quotaRepo;
    private final OrganizationRepository orgRepo;

    public AdminQuotaController(OrgQuotaRepository quotaRepo, OrganizationRepository orgRepo) {
        this.quotaRepo = quotaRepo;
        this.orgRepo = orgRepo;
    }

    @GetMapping("/{orgId}")
    public ResponseEntity<AdminDtos.OrgQuotaSummary> get(@PathVariable UUID orgId) {
        if (orgRepo.findById(orgId).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(toDto(quotaRepo.findById(orgId).orElse(null)));
    }

    @PutMapping("/{orgId}")
    public ResponseEntity<AdminDtos.OrgQuotaSummary> upsert(
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
        return ResponseEntity.ok(toDto(q));
    }

    private static AdminDtos.OrgQuotaSummary toDto(OrgQuota q) {
        if (q == null) return new AdminDtos.OrgQuotaSummary(null, null, null, false, null);
        return new AdminDtos.OrgQuotaSummary(
                q.getApiRpmOverride(), q.getApiDailyCap(), q.getPdfDailyCap(),
                q.isFrozen(), q.getFrozenReason());
    }
}
