package com.agreemint.api.dto;

import com.agreemint.domain.WebhookDelivery;

import java.time.Instant;
import java.util.UUID;

public record WebhookDeliveryResponse(
        UUID id,
        UUID webhookId,
        String event,
        int attempt,
        int maxAttempts,
        String status,
        Integer responseCode,
        String responseBody,
        String error,
        Instant nextRetryAt,
        Instant createdAt,
        Instant deliveredAt
) {
    public static WebhookDeliveryResponse from(WebhookDelivery d) {
        return new WebhookDeliveryResponse(
                d.getId(),
                d.getWebhookId(),
                d.getEvent(),
                d.getAttempt(),
                d.getMaxAttempts(),
                d.getStatus().name(),
                d.getResponseCode(),
                d.getResponseBody(),
                d.getError(),
                d.getNextRetryAt(),
                d.getCreatedAt(),
                d.getDeliveredAt()
        );
    }
}
