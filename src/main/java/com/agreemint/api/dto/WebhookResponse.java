package com.agreemint.api.dto;

import com.agreemint.domain.Webhook;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Metadata-only view of a webhook — the {@code secret} column is deliberately
 * omitted. Raw secret is returned exactly once, by {@link WebhookCreatedResponse}.
 */
public record WebhookResponse(
        UUID id,
        UUID orgId,
        String url,
        String secretLast4,
        List<String> events,
        boolean active,
        Instant createdAt,
        Instant revokedAt
) {
    public static WebhookResponse from(Webhook w) {
        return new WebhookResponse(
                w.getId(),
                w.getOrgId(),
                w.getUrl(),
                w.getSecretLast4(),
                List.copyOf(w.eventSet()),
                w.isActive(),
                w.getCreatedAt(),
                w.getRevokedAt()
        );
    }
}
