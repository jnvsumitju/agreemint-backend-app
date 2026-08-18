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
        boolean hasUncommittedChanges,
        /**
         * Preview image, or null when none has been rendered yet.
         *
         * <p>A short-lived presigned URL, minted per response. Never persisted:
         * the signature expires, so a stored URL would be a link that silently
         * stops working. Prefers the in-progress image over the committed one,
         * so the list shows what the template looks like now.
         */
        String thumbnailUrl
) {
}
