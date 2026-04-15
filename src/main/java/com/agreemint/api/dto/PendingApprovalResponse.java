package com.agreemint.api.dto;

import java.time.Instant;
import java.util.UUID;

public record PendingApprovalResponse(
        UUID stepId,
        UUID documentId,
        String documentTitle,
        String roleLabel,
        Instant requestedAt
) {
}
