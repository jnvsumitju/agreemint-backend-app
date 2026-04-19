package com.agreemint.admin.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Record-bundle for the admin portal's wire formats. Kept together in one
 * file because each DTO is small + mostly shaped like the underlying row.
 */
public final class AdminDtos {

    private AdminDtos() {}

    /** Row in the orgs list. */
    public record OrgSummary(
            UUID id,
            String name,
            String slug,
            Instant createdAt,
            int memberCount,
            int templateCount,
            long docsLast30d,
            boolean frozen
    ) {}

    /** Deep view of one org — populates the Org Detail page. */
    public record OrgDetail(
            UUID id,
            String name,
            String slug,
            Instant createdAt,
            List<OrgMember> members,
            List<OrgTemplate> templates,
            List<OrgApiKey> apiKeys,
            OrgQuotaSummary quota,
            long docsLast30d
    ) {}

    public record OrgMember(UUID userId, String email, String name, String role, Instant joinedAt) {}
    public record OrgTemplate(UUID id, String name, Instant createdAt, Integer latestVersion) {}
    public record OrgApiKey(UUID id, String name, String prefix, String last4, List<String> scopes, Instant createdAt, Instant expiresAt, Instant lastUsedAt) {}

    public record OrgQuotaSummary(
            Integer apiRpmOverride,
            Integer apiDailyCap,
            Integer pdfDailyCap,
            boolean frozen,
            String frozenReason
    ) {}

    /** Row in the users list. */
    public record UserSummary(
            UUID id,
            String email,
            String name,
            Instant createdAt,
            Instant lastLoginAt,
            boolean emailVerified,
            boolean staff,
            int orgCount
    ) {}

    /** Deep view of one user. */
    public record UserDetail(
            UUID id,
            String email,
            String name,
            String avatarUrl,
            Instant createdAt,
            Instant lastLoginAt,
            boolean emailVerified,
            boolean staff,
            List<UserOrg> orgs
    ) {}

    public record UserOrg(UUID orgId, String orgName, String role, Instant joinedAt) {}

    /** One row in the audit log. Shape mirrors the activity_log table. */
    public record AuditEvent(
            UUID id,
            UUID orgId,
            String orgName,
            UUID userId,
            String userName,
            String action,
            String entityType,
            UUID entityId,
            String entityName,
            Instant createdAt
    ) {}

    /** Announcement create / update / response. */
    public record AnnouncementRequest(
            String title,
            String body,
            String severity,
            List<UUID> targetOrgIds,
            Instant startsAt,
            Instant endsAt,
            boolean active
    ) {}

    public record AnnouncementResponse(
            UUID id,
            String title,
            String body,
            String severity,
            List<UUID> targetOrgIds,
            boolean active,
            Instant startsAt,
            Instant endsAt,
            Instant createdAt,
            UUID createdBy
    ) {}

    /** Feature flag row + its per-org overrides. */
    public record FeatureFlagResponse(
            String key,
            String description,
            boolean defaultEnabled,
            List<FeatureFlagOverrideResponse> overrides
    ) {}

    public record FeatureFlagOverrideResponse(UUID orgId, String orgName, boolean enabled) {}

    public record FeatureFlagUpsertRequest(String key, String description, boolean defaultEnabled) {}
    public record FeatureFlagOverrideRequest(UUID orgId, boolean enabled) {}

    /** Usage dashboard payload. */
    public record UsageSummary(
            long totalOrgs,
            long totalUsers,
            long totalTemplates,
            long docsLast30d,
            long apiCallsLast30d,
            List<UsageBucket> dailyDocs,  // last 30 days, oldest first
            List<OrgUsageRow> topOrgs
    ) {}

    public record UsageBucket(String day, long docs) {}
    public record OrgUsageRow(UUID orgId, String orgName, long docsLast30d, long apiCallsLast30d) {}

    /** Platform-health metrics displayed on the Health page. */
    public record PlatformHealth(
            long pendingWebhookDeliveries,
            long pendingExports,
            long failedJobsLast24h,
            long activeApiKeys,
            long recentFailedLogins24h,
            List<HealthIssue> recentIssues
    ) {}

    public record HealthIssue(String severity, String message, Instant at) {}

    /** Impersonation request + response. `ttlMinutes` must be ≤ 60. */
    public record ImpersonationRequest(UUID targetUserId, UUID targetOrgId, Integer ttlMinutes) {}
    public record ImpersonationResponse(String accessToken, Instant expiresAt, UUID targetUserId, UUID impersonatedBy) {}

    /** Quota update payload. Null fields clear the override. */
    public record OrgQuotaRequest(
            Integer apiRpmOverride,
            Integer apiDailyCap,
            Integer pdfDailyCap,
            Boolean frozen,
            String frozenReason
    ) {}

    /** Export lifecycle. */
    public record ExportRequest(String scope, UUID targetId) {}
    public record ExportResponse(UUID id, String scope, UUID targetId, String status, String fileUrl, Instant requestedAt, Instant completedAt) {}

    /** Email template override. */
    public record EmailTemplateResponse(String key, String subject, String bodyHtml, Instant updatedAt) {}
    public record EmailTemplateUpsertRequest(String subject, String bodyHtml) {}
}
