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
        /** Only ever false on /mine — the browse list returns published rows only. */
        boolean published,
        Instant createdAt
) {
}
