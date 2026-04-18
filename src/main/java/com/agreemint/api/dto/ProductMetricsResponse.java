package com.agreemint.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A product row enriched with the counts used by the Products page —
 * template count + documents split by source + last-generated timestamp.
 * All counts are org-scoped and computed from live data; no caching.
 */
public record ProductMetricsResponse(
        UUID id,
        String name,
        String description,
        long templateCount,
        long documentCount,
        long uiDocumentCount,
        long apiDocumentCount,
        Instant lastDocumentAt,
        Instant createdAt
) {
}
