package com.agreemint.admin.api;

import com.agreemint.admin.api.dto.AdminDtos;
import com.agreemint.admin.domain.OrgQuota;
import com.agreemint.admin.repository.OrgQuotaRepository;
import com.agreemint.domain.ApiKey;
import com.agreemint.domain.OrgMembership;
import com.agreemint.domain.Organization;
import com.agreemint.domain.Template;
import com.agreemint.repository.ApiKeyRepository;
import com.agreemint.repository.OrgMembershipRepository;
import com.agreemint.repository.OrganizationRepository;
import com.agreemint.repository.TemplateRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin portal — organizations listing + detail. All routes are gated on
 * {@code ROLE_STAFF} via SecurityConfig, so no per-method role check needed.
 */
@Tag(name = "Admin · Organizations")
@RestController
@RequestMapping("/api/admin/orgs")
public class AdminOrgController {

    private final OrganizationRepository orgRepo;
    private final OrgMembershipRepository membershipRepo;
    private final TemplateRepository templateRepo;
    private final ApiKeyRepository apiKeyRepo;
    private final OrgQuotaRepository quotaRepo;

    public AdminOrgController(
            OrganizationRepository orgRepo,
            OrgMembershipRepository membershipRepo,
            TemplateRepository templateRepo,
            ApiKeyRepository apiKeyRepo,
            OrgQuotaRepository quotaRepo) {
        this.orgRepo = orgRepo;
        this.membershipRepo = membershipRepo;
        this.templateRepo = templateRepo;
        this.apiKeyRepo = apiKeyRepo;
        this.quotaRepo = quotaRepo;
    }

    @GetMapping
    public List<AdminDtos.OrgSummary> list() {
        List<Organization> orgs = orgRepo.findAll();
        List<UUID> orgIds = orgs.stream().map(Organization::getId).toList();

        // Batch member counts: one group-by query. N+1 is only an issue at
        // scale; for the admin list where ops skim all orgs it's fine for
        // now, and we can swap for a native aggregate later.
        Map<UUID, Integer> memberCounts = new HashMap<>();
        Map<UUID, Integer> templateCounts = new HashMap<>();
        for (UUID oid : orgIds) {
            memberCounts.put(oid, membershipRepo.findByOrganizationId(oid).size());
            templateCounts.put(oid, templateRepo.findByOrgIdOrderByCreatedAtDesc(oid).size());
        }

        // Frozen state from org_quotas.
        Map<UUID, Boolean> frozenMap = new HashMap<>();
        quotaRepo.findAllById(orgIds).forEach(q -> frozenMap.put(q.getOrgId(), q.isFrozen()));

        return orgs.stream()
                .map(o -> new AdminDtos.OrgSummary(
                        o.getId(),
                        o.getName(),
                        o.getSlug(),
                        o.getCreatedAt(),
                        memberCounts.getOrDefault(o.getId(), 0),
                        templateCounts.getOrDefault(o.getId(), 0),
                        // docsLast30d: TODO wire GeneratedDocumentRepository aggregate
                        // once we're sure of the index. Stubbed to 0 for now.
                        0L,
                        frozenMap.getOrDefault(o.getId(), false)))
                .sorted(Comparator.comparing(AdminDtos.OrgSummary::createdAt).reversed())
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminDtos.OrgDetail> detail(@PathVariable UUID id) {
        Optional<Organization> maybe = orgRepo.findById(id);
        if (maybe.isEmpty()) return ResponseEntity.notFound().build();
        Organization o = maybe.get();

        List<AdminDtos.OrgMember> members = membershipRepo.findByOrganizationId(id).stream()
                .map((OrgMembership m) -> new AdminDtos.OrgMember(
                        m.getUser().getId(),
                        m.getUser().getEmail(),
                        m.getUser().getName(),
                        m.getRole().name(),
                        m.getCreatedAt()))
                .collect(Collectors.toList());

        List<AdminDtos.OrgTemplate> templates = templateRepo.findByOrgIdOrderByCreatedAtDesc(id).stream()
                .map((Template t) -> new AdminDtos.OrgTemplate(
                        t.getId(), t.getName(), t.getCreatedAt(),
                        // latestVersion: TODO query TemplateVersionRepository once
                        // we're sure which lookup we want. Leaving null for now
                        // renders as "—" in the UI.
                        null))
                .collect(Collectors.toList());

        List<AdminDtos.OrgApiKey> apiKeys = apiKeyRepo.findByOrgIdOrderByCreatedAtDesc(id).stream()
                .filter(k -> k.getRevokedAt() == null)
                .map((ApiKey k) -> new AdminDtos.OrgApiKey(
                        k.getId(), k.getName(), k.getKeyPrefix(), k.getKeyLast4(),
                        Arrays.stream(k.getScopes().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                        k.getCreatedAt(), k.getExpiresAt(), k.getLastUsedAt()))
                .collect(Collectors.toList());

        OrgQuota quota = quotaRepo.findById(id).orElse(null);
        AdminDtos.OrgQuotaSummary qsum = quota != null
                ? new AdminDtos.OrgQuotaSummary(quota.getApiRpmOverride(), quota.getApiDailyCap(),
                        quota.getPdfDailyCap(), quota.isFrozen(), quota.getFrozenReason())
                : new AdminDtos.OrgQuotaSummary(null, null, null, false, null);

        return ResponseEntity.ok(new AdminDtos.OrgDetail(
                o.getId(), o.getName(), o.getSlug(), o.getCreatedAt(),
                members, templates, apiKeys, qsum,
                0L /* docsLast30d TODO */));
    }
}
