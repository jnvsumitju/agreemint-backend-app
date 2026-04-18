package com.agreemint.api.dto;

import java.time.Instant;
import java.util.UUID;

public record TemplateResponse(
        UUID id,
        String name,
        String createdBy,
        Instant createdAt,
        UUID productId,
        String productName
) {
}
