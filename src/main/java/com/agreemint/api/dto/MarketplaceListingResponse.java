package com.agreemint.api.dto;

import java.time.Instant;
import java.util.UUID;

public record MarketplaceListingResponse(
        UUID id,
        String type,
        String title,
        String description,
        String authorName,
        String thumbnailUrl,
        String category,
        String tags,
        int installCount,
        Instant createdAt
) {
}
