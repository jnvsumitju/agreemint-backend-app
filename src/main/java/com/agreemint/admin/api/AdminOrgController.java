package com.agreemint.admin.api;

import com.agreemint.repository.GeneratedDocumentRepository;
import org.springframework.web.bind.annotation.RequestParam;
import java.time.temporal.ChronoUnit;
import java.time.Instant;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Page;
import com.agreemint.admin.api.dto.PageResponse;
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
    private final GeneratedDocumentRepository docRepo;

    public AdminOrgController(
            OrganizationRepository orgRepo,
            OrgMembershipRepository membershipRepo,
            TemplateRepository templateRepo,
            ApiKeyRepository apiKeyRepo,
            OrgQuotaRepository quotaRepo,
            GeneratedDocumentRepository docRepo) {
        this.orgRepo = orgRepo;
        this.membershipRepo = membershipRepo;
        this.templateRepo = templateRepo;
        this.apiKeyRepo = apiKeyRepo;
        this.quotaRepo = quotaRepo;
        this.docRepo = docRepo;
    }

    @GetMapping
    public PageResponse<AdminDtos.OrgSummary> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        int pageSize = Math.min(200, Math.max(1, size));
        // "" not null: a null bound into LOWER() has no type on Postgres and
        // the server infers bytea, so the unfiltered list 500s. See the repository.
        String search = (q == null || q.isBlank()) ? "" : q.trim();

        // Paged in the DB and sorted in the DB. This previously loaded every
        // organisation and sorted in memory.
        Page<Organization> orgs = orgRepo.search(search,
                PageRequest.of(Math.max(0, page), pageSize,
                        Sort.by(Sort.Direction.DESC, "createdAt")));

        List<UUID> orgIds = orgs.getContent().stream().map(Organization::getId).toList();
        if (orgIds.isEmpty()) {
            return PageResponse.of(orgs, List.of());
        }

        // Three grouped queries for the whole page, replacing two queries per
        // org. The old code claimed to do this but ran a loop.
        Map<UUID, Long> memberCounts = toCountMap(membershipRepo.countByOrgIds(orgIds));
        Map<UUID, Long> templateCounts = toCountMap(templateRepo.countByOrgIds(orgIds));
        Map<UUID, Long> docCounts = toCountMap(docRepo.countByOrgIdsSince(
                orgIds, Instant.now().minus(30, ChronoUnit.DAYS)));

        Map<UUID, Boolean> frozenMap = new HashMap<>();
        quotaRepo.findAllById(orgIds).forEach(qt -> frozenMap.put(qt.getOrgId(), qt.isFrozen()));

        List<AdminDtos.OrgSummary> items = orgs.getContent().stream()
                .map(o -> new AdminDtos.OrgSummary(
                        o.getId(),
                        o.getName(),
                        o.getSlug(),
                        o.getPlan() == null ? "FREE" : o.getPlan().name(),
                        o.getCreatedAt(),
                        memberCounts.getOrDefault(o.getId(), 0L).intValue(),
                        templateCounts.getOrDefault(o.getId(), 0L).intValue(),
                        docCounts.getOrDefault(o.getId(), 0L),
                        frozenMap.getOrDefault(o.getId(), false)))
                .toList();

        return PageResponse.of(orgs, items);
    }

    /** Collapse a grouped-count result — rows of (UUID id, Long count). */
    private static Map<UUID, Long> toCountMap(List<Object[]> rows) {
        Map<UUID, Long> out = new HashMap<>();
        for (Object[] row : rows) {
            out.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return out;
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminDtos.OrgDetail> detail(@PathVariable UUID id) {
        Optional<Organization> maybe = orgRepo.findById(id);
        if (maybe.isEmpty()) return ResponseEntity.notFound().build();
        Organization o = maybe.get();

        // findWithUserByOrganizationId, not findByOrganizationId: this method has
        // no transaction and open-in-view is off, so a LAZY user proxy would be
        // detached by the time getEmail() is called below.
        List<AdminDtos.OrgMember> members = membershipRepo.findWithUserByOrganizationId(id).stream()
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

        long docsLast30d = docRepo
                .countByOrgIdsSince(List.of(id), Instant.now().minus(30, ChronoUnit.DAYS))
                .stream()
                .findFirst()
                .map(row -> ((Number) row[1]).longValue())
                .orElse(0L);

        return ResponseEntity.ok(new AdminDtos.OrgDetail(
                o.getId(), o.getName(), o.getSlug(),
                o.getPlan() == null ? "FREE" : o.getPlan().name(),
                o.getCreatedAt(),
                members, templates, apiKeys, qsum,
                docsLast30d));
    }
}
