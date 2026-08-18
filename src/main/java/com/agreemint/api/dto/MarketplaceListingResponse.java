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
        /** First-party listing from Crixaa: badged in the UI, and free on every plan. */
        boolean official,
        /**
         * The template this listing was published from — populated ONLY for
         * official listings, null for everything else.
         *
         * <p>Staff maintain the first-party catalogue by opening this template
         * directly, and for those rows the id belongs to Crixaa's own workspace,
         * so publishing it discloses nothing. For a third-party listing it is
         * another customer's internal template id, and handing that to every
         * browser is a needless leak — the console has no use for it, and an id
         * is a lookup key for anyone probing for an authorization gap.
         */
        UUID sourceTemplateId,
        Instant createdAt
) {
}
