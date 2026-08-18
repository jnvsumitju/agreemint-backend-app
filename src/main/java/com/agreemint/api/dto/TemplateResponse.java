package com.agreemint.api.dto;

import java.time.Instant;
import java.util.UUID;

public record TemplateResponse(
        UUID id,
        String name,
        String createdBy,
        Instant createdAt,
        UUID productId,
        String productName,
        /**
         * Highest committed version, or null when nothing has ever been committed.
         *
         * <p>Documents generate from a committed version, so a null here means
         * this template cannot produce output yet — which the list previously
         * gave no way to see.
         */
        /** Lifecycle state set by an author: DRAFT, ACTIVE or ARCHIVED. */
        com.agreemint.domain.TemplateStatus status,
        Integer versionNumber,
        /**
         * A draft exists, i.e. there are editor changes not in any version.
         *
         * <p>Committing deletes the draft row, so the row's existence is the
         * signal — no timestamp comparison, and no window where the two
         * disagree. This is the state that matters day to day: a template
         * showing v2 whose newer edits are not in the v2 documents being
         * generated from it.
         */
        boolean hasUncommittedChanges
) {
}
