package com.agreemint.api.dto;

import com.agreemint.domain.Product;

import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        UUID orgId,
        String name,
        String description,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProductResponse from(Product p) {
        return new ProductResponse(
                p.getId(),
                p.getOrgId(),
                p.getName(),
                p.getDescription(),
                p.getCreatedBy(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }
}
