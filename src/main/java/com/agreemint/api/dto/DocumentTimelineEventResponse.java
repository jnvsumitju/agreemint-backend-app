package com.agreemint.api.dto;

import com.agreemint.domain.DocumentLifecycleEvent;
import com.agreemint.domain.LifecycleStatus;

import java.time.Instant;
import java.util.UUID;

public record DocumentTimelineEventResponse(
        UUID id,
        LifecycleStatus fromStatus,
        LifecycleStatus toStatus,
        String eventType,
        String actorName,
        String comment,
        Instant createdAt
) {
    public static DocumentTimelineEventResponse from(DocumentLifecycleEvent e) {
        return new DocumentTimelineEventResponse(
                e.getId(),
                e.getFromStatus(),
                e.getToStatus(),
                e.getEventType(),
                e.getActorName(),
                e.getComment(),
                e.getCreatedAt()
        );
    }
}
