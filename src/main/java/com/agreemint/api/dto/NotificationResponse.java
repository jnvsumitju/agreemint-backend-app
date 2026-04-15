package com.agreemint.api.dto;

import com.agreemint.domain.Notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String type,
        String title,
        String body,
        String entityType,
        UUID entityId,
        boolean read,
        Instant createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(), n.getType(), n.getTitle(), n.getBody(),
                n.getEntityType(), n.getEntityId(), n.isRead(), n.getCreatedAt()
        );
    }
}
