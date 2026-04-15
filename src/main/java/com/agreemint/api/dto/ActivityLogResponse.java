package com.agreemint.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ActivityLogResponse(
        UUID id,
        String action,
        String entityType,
        UUID entityId,
        String entityName,
        String userName,
        Instant createdAt
) {
}
