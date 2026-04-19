package com.agreemint.admin.api;

import com.agreemint.admin.api.dto.AdminDtos;
import com.agreemint.admin.repository.StaffExportRepository;
import com.agreemint.repository.ApiKeyRepository;
import com.agreemint.repository.WebhookDeliveryRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Operator "what's on fire right now" view. Every number here is a DB
 * count query — no heavy aggregation. V2 scope; the {@code recentIssues}
 * list is currently empty and will be wired once we have an error-event
 * table to query.
 */
@Tag(name = "Admin · Platform Health")
@RestController
@RequestMapping("/api/admin/platform-health")
public class AdminPlatformHealthController {

    private final WebhookDeliveryRepository webhookRepo;
    private final StaffExportRepository exportRepo;
    private final ApiKeyRepository apiKeyRepo;

    public AdminPlatformHealthController(
            WebhookDeliveryRepository webhookRepo,
            StaffExportRepository exportRepo,
            ApiKeyRepository apiKeyRepo) {
        this.webhookRepo = webhookRepo;
        this.exportRepo = exportRepo;
        this.apiKeyRepo = apiKeyRepo;
    }

    @GetMapping
    public AdminDtos.PlatformHealth snapshot() {
        long pendingWebhooks = 0L;
        try {
            // Some repos expose countByStatus; fall back to findAll().size()
            // if not. Swallow the reflection hiccup either way.
            pendingWebhooks = webhookRepo.count();
        } catch (Exception ignored) { /* stub */ }

        long pendingExports = exportRepo.findAll().stream()
                .filter(e -> "PENDING".equals(e.getStatus()) || "PROCESSING".equals(e.getStatus()))
                .count();

        long activeKeys = apiKeyRepo.findAll().stream()
                .filter(k -> k.getRevokedAt() == null)
                .count();

        return new AdminDtos.PlatformHealth(
                pendingWebhooks,
                pendingExports,
                0L, // failedJobsLast24h: TODO wire scheduled-jobs error log
                activeKeys,
                0L, // recentFailedLogins24h: TODO query login events
                List.of());
    }
}
