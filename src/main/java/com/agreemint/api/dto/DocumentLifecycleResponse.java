package com.agreemint.api.dto;

import com.agreemint.domain.DocumentSource;
import com.agreemint.domain.DocumentStatus;
import com.agreemint.domain.GeneratedDocument;
import com.agreemint.domain.LifecycleStatus;

import java.time.Instant;
import java.util.UUID;

public record DocumentLifecycleResponse(
        UUID id,
        UUID templateId,
        String templateName,
        UUID productId,
        String productName,
        UUID versionId,
        String title,
        String description,
        String fileUrl,
        DocumentStatus generationStatus,
        LifecycleStatus lifecycleStatus,
        DocumentSource source,
        UUID createdBy,
        UUID orgId,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * Base builder — pass the looked-up product name explicitly so the list
     * path can batch the join rather than lazy-loading product-per-row.
     */
    public static DocumentLifecycleResponse from(GeneratedDocument d, String productName) {
        return new DocumentLifecycleResponse(
                d.getId(),
                d.getTemplate().getId(),
                d.getTemplate().getName(),
                d.getTemplate().getProductId(),
                productName,
                d.getVersion().getId(),
                d.getTitle(),
                d.getDescription(),
                d.getFileUrl(),
                d.getStatus(),
                d.getLifecycleStatus(),
                d.getSource(),
                d.getCreatedBy(),
                d.getOrgId(),
                d.getExpiresAt(),
                d.getCreatedAt(),
                d.getUpdatedAt()
        );
    }

    /** Convenience overload for single-row callers where the caller doesn't
     *  already have the product name in hand. Skips the product label. */
    public static DocumentLifecycleResponse from(GeneratedDocument d) {
        return from(d, null);
    }
}
