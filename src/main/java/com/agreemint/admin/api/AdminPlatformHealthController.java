package com.agreemint.admin.api;

import java.time.temporal.ChronoUnit;
import java.time.Instant;
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
        // countByStatus exists — the previous comment claimed it might not and
        // fell back to count(), which is every delivery ever made. The Overview
        // tile read "Pending webhooks: 41,982" on a healthy queue.
        long pendingWebhooks = webhookRepo.countByStatus(
                com.agreemint.domain.WebhookDelivery.Status.PENDING);

        long pendingExports = exportRepo.findAll().stream()
                .filter(e -> "PENDING".equals(e.getStatus()) || "PROCESSING".equals(e.getStatus()))
                .count();

        // Expiry counts too. ApiKey.isActive() — the definition the auth filter
        // enforces — treats an expired key as inactive, so counting only
        // revocation here reported keys as active that could not authenticate.
        Instant now = Instant.now();
        long activeKeys = apiKeyRepo.findAll().stream()
                .filter(k -> k.getRevokedAt() == null)
                .filter(k -> k.getExpiresAt() == null || k.getExpiresAt().isAfter(now))
                .count();

        // Failures across the two queues we actually record outcomes for.
        Instant since24h = Instant.now().minus(24, ChronoUnit.HOURS);
        long failedLast24h =
                webhookRepo.countByStatusAndCreatedAtAfter(
                        com.agreemint.domain.WebhookDelivery.Status.ABANDONED, since24h)
                + exportRepo.findAll().stream()
                        .filter(e -> "FAILED".equals(e.getStatus()))
                        .filter(e -> e.getCompletedAt() != null && e.getCompletedAt().isAfter(since24h))
                        .count();

        return new AdminDtos.PlatformHealth(
                pendingWebhooks,
                pendingExports,
                failedLast24h,
                activeKeys);
    }
}
