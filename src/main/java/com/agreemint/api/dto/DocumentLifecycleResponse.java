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
    public static DocumentLifecycleResponse from(GeneratedDocument d) {
        return new DocumentLifecycleResponse(
                d.getId(),
                d.getTemplate().getId(),
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
}
