package com.agreemint.api.dto;

import com.agreemint.domain.TemplateShare;

import java.time.Instant;
import java.util.UUID;

public record TemplateShareResponse(
        UUID id,
        UUID templateId,
        String sharedWithEmail,
        UUID sharedWithUserId,
        String role,
        String shareToken,
        Instant expiresAt,
        Instant createdAt
) {
    public static TemplateShareResponse from(TemplateShare s) {
        return new TemplateShareResponse(
                s.getId(), s.getTemplateId(), s.getSharedWithEmail(),
                s.getSharedWithUserId(), s.getRole().name(),
                s.getShareToken(), s.getExpiresAt(), s.getCreatedAt()
        );
    }
}
