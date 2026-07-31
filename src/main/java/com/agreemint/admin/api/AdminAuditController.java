package com.agreemint.admin.api;

import java.util.stream.Collectors;
import java.util.Set;
import java.util.Objects;
import org.springframework.data.domain.Page;
import com.agreemint.admin.api.dto.PageResponse;
import com.agreemint.admin.api.dto.AdminDtos;
import com.agreemint.domain.ActivityLog;
import com.agreemint.domain.Organization;
import com.agreemint.repository.ActivityLogRepository;
import com.agreemint.repository.OrganizationRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-org audit log view. The existing {@code ActivityLogRepository} is
 * scoped to a single org — here we just call {@code findAll(Pageable)} on
 * the full table and filter in-memory. That's fine until the table gets
 * very large; we can add a native-query repo method when we feel it.
 */
@Tag(name = "Admin · Audit")
@RestController
@RequestMapping("/api/admin/audit")
public class AdminAuditController {

    private final ActivityLogRepository auditRepo;
    private final OrganizationRepository orgRepo;

    public AdminAuditController(ActivityLogRepository auditRepo, OrganizationRepository orgRepo) {
        this.auditRepo = auditRepo;
        this.orgRepo = orgRepo;
    }

    @GetMapping
    public PageResponse<AdminDtos.AuditEvent> list(
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {

        int pageSize = Math.min(500, Math.max(1, size));
        String actionFilter = (action == null || action.isBlank()) ? null : action.trim();

        // Filters go into the query. Filtering after a global limit — which is
        // what this did before — meant a search scoped to one org usually came
        // back empty because the matching rows were outside the window.
        Page<ActivityLog> events = auditRepo.search(
                orgId, userId, actionFilter,
                PageRequest.of(Math.max(0, page), pageSize,
                        Sort.by(Sort.Direction.DESC, "createdAt")));

        // Resolve only the org names on this page rather than loading the whole
        // organisations table on every request.
        Set<UUID> orgIds = events.getContent().stream()
                .map(ActivityLog::getOrgId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> orgNames = orgRepo.findAllById(orgIds).stream()
                .collect(Collectors.toMap(Organization::getId, Organization::getName));

        return PageResponse.of(events, e -> new AdminDtos.AuditEvent(
                e.getId(),
                e.getOrgId(),
                orgNames.get(e.getOrgId()),
                e.getUserId(),
                e.getUserName(),
                e.getAction(),
                e.getEntityType(),
                e.getEntityId(),
                e.getEntityName(),
                e.getCreatedAt(),
                impersonatorOf(e.getMetadata())));
    }

    /**
     * Pull the operator out of the metadata blob, or null.
     *
     * <p>Tolerant on purpose: metadata is a free-form text column other writers
     * also use, so anything unparseable simply means "not impersonated" rather
     * than failing the whole audit page.
     */
    private static UUID impersonatorOf(String metadata) {
        if (metadata == null || !metadata.contains("impersonatedBy")) return null;
        java.util.regex.Matcher m = IMPERSONATED_BY.matcher(metadata);
        if (!m.find()) return null;
        try {
            return UUID.fromString(m.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final java.util.regex.Pattern IMPERSONATED_BY =
            java.util.regex.Pattern.compile("\"impersonatedBy\"\\s*:\\s*\"([^\"]+)\"");
}
