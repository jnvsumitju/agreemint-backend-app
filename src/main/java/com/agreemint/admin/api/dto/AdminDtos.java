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
            String plan,
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
            String plan,
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
            Instant createdAt,
            /**
             * Operator behind an impersonated action, or null for an ordinary one.
             *
             * <p>ActivityService has always stamped this into
             * {@code activity_log.metadata}, but nothing read it back — every
             * action taken during a support session displayed as the customer's
             * own. The whole point of 0e was attribution, and it stopped one
             * layer short of anyone being able to see it.
             */
            UUID impersonatedBy
    ) {}

    /** Announcement create / update / response. */
    public record AnnouncementRequest(
            @jakarta.validation.constraints.NotBlank(message = "title is required")
            @jakarta.validation.constraints.Size(max = 200) String title,
            @jakarta.validation.constraints.NotBlank(message = "body is required") String body,
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

    public record FeatureFlagUpsertRequest(
            @jakarta.validation.constraints.NotBlank(message = "key is required")
            @jakarta.validation.constraints.Size(max = 64) String key,
            String description,
            boolean defaultEnabled) {}
    /**
     * @param enabled null is rejected rather than silently meaning false — the
     *                DELETE endpoint is how an override is removed. As a
     *                primitive this was indistinguishable from an explicit off.
     */
    public record FeatureFlagOverrideRequest(UUID orgId, Boolean enabled) {}

    /**
     * Usage dashboard payload.
     *
     * <p>{@code apiCallsLast30d} was removed rather than kept at a hardcoded
     * zero: API traffic is counted by Bucket4j in Redis with a short TTL and is
     * not recorded anywhere durable, so the figure cannot be produced. A field
     * that always reads 0 is worse than an absent one — it looks like an answer.
     */
    public record UsageSummary(
            long totalOrgs,
            long totalUsers,
            long totalTemplates,
            long docsLast30d,
            List<UsageBucket> dailyDocs,  // last 30 days, oldest first
            List<OrgUsageRow> topOrgs
    ) {}

    public record UsageBucket(String day, long docs) {}
    public record OrgUsageRow(UUID orgId, String orgName, long docsLast30d) {}

    /**
     * Platform-health metrics.
     *
     * <p>{@code recentFailedLogins24h} and {@code recentIssues} were removed:
     * neither failed logins nor error events are recorded anywhere, so both
     * were permanently 0 and []. They can come back when there is a table
     * behind them.
     */
    public record PlatformHealth(
            long pendingWebhookDeliveries,
            long pendingExports,
            long failedJobsLast24h,
            long activeApiKeys
    ) {}

    /** Impersonation request + response. `ttlMinutes` must be ≤ 60. */
    public record ImpersonationRequest(
            @jakarta.validation.constraints.NotNull(message = "targetUserId is required") UUID targetUserId,
            UUID targetOrgId,
            Integer ttlMinutes) {}
    /**
     * @param sessionId hand back so the operator can end the session early;
     *                  without it a session could only be waited out.
     */
    public record ImpersonationResponse(String accessToken, Instant expiresAt, UUID targetUserId,
                                        UUID impersonatedBy, String sessionId) {}

    /** Quota update payload. Null fields clear the override. */
    public record OrgQuotaRequest(
            Integer apiRpmOverride,
            Integer apiDailyCap,
            Integer pdfDailyCap,
            Boolean frozen,
            String frozenReason
    ) {}

    /**
     * A quota as the admin portal needs to show it: the staff override, plus
     * what is <em>actually in force</em> once the plan and system defaults are
     * applied.
     *
     * <p>The override alone is not enough to render honestly. A null
     * {@code apiDailyCap} means "inherit", and staff need to see the number
     * being inherited before deciding whether to change it — otherwise the
     * screen shows a blank field next to no indication of the real limit.
     *
     * @param override           the raw per-org row, all-null when none exists
     * @param plan               the org's current plan, which supplies the middle tier
     * @param effectiveApiDailyMax daily API requests actually enforced
     * @param effectivePdfDailyMax daily documents actually enforced, null = uncapped
     * @param systemApiDailyMax  the fallback used when neither override nor plan sets one
     * @param pdfRemainingToday  documents left in the current rolling day, null when uncapped
     */
    public record OrgQuotaView(
            OrgQuotaSummary override,
            String plan,
            Integer effectiveApiDailyMax,
            Integer effectivePdfDailyMax,
            long systemApiDailyMax,
            Long pdfRemainingToday
    ) {}

    /** Export lifecycle. */
    public record ExportRequest(
            @jakarta.validation.constraints.NotBlank(message = "scope is required") String scope,
            UUID targetId) {}
    /**
     * @param error why a FAILED export failed. Previously omitted, which left
     *              the UI showing a red status with no explanation.
     */
    public record ExportResponse(UUID id, String scope, UUID targetId, String status, String fileUrl,
                                 String error, Instant requestedAt, Instant completedAt) {}

    /** Email template override. */
    public record EmailTemplateResponse(String key, String subject, String bodyHtml, Instant updatedAt) {}
    public record EmailTemplateUpsertRequest(
            @jakarta.validation.constraints.Size(max = 200) String subject,
            @jakarta.validation.constraints.NotBlank(message = "bodyHtml is required") String bodyHtml) {}
}
