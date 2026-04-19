package com.agreemint.admin.api;

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
    public List<AdminDtos.AuditEvent> list(
            @RequestParam(required = false) UUID orgId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "100") int limit) {
        int size = Math.min(500, Math.max(1, limit));
        List<ActivityLog> events = auditRepo.findAll(
                PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();

        Map<UUID, String> orgNames = new HashMap<>();
        for (Organization o : orgRepo.findAll()) orgNames.put(o.getId(), o.getName());

        return events.stream()
                .filter(e -> orgId == null || orgId.equals(e.getOrgId()))
                .filter(e -> userId == null || userId.equals(e.getUserId()))
                .filter(e -> action == null || action.isBlank() || action.equalsIgnoreCase(e.getAction()))
                .map(e -> new AdminDtos.AuditEvent(
                        e.getId(),
                        e.getOrgId(),
                        orgNames.get(e.getOrgId()),
                        e.getUserId(),
                        e.getUserName(),
                        e.getAction(),
                        e.getEntityType(),
                        e.getEntityId(),
                        e.getEntityName(),
                        e.getCreatedAt()))
                .toList();
    }
}
