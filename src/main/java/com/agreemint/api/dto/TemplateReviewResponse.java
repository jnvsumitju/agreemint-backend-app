package com.agreemint.api.dto;

import com.agreemint.domain.ReviewStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * API response for a {@link com.agreemint.domain.TemplateReview}, denormalised
 * with reviewer/requester profile bits (name + avatar) so the UI can render
 * without an N+1 fetch.
 */
public record TemplateReviewResponse(
        UUID id,
        UUID templateId,
        UUID versionId,
        int versionNumber,
        ReviewerInfo requester,
        ReviewerInfo reviewer,
        ReviewStatus status,
        String message,
        String summary,
        Instant createdAt,
        Instant decidedAt
) {
    public record ReviewerInfo(UUID id, String name, String email, String avatarUrl) {}
}
