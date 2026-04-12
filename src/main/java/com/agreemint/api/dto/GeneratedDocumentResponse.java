package com.agreemint.api.dto;

import com.agreemint.domain.DocumentStatus;

import java.time.Instant;
import java.util.UUID;

public record GeneratedDocumentResponse(
        UUID id,
        UUID templateId,
        UUID versionId,
        String fileUrl,
        DocumentStatus status,
        Instant createdAt
) {
}
