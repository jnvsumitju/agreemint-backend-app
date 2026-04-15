package com.agreemint.api.dto;

import com.agreemint.domain.ApprovalStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApprovalWorkflowResponse(
        UUID id,
        UUID documentId,
        ApprovalStatus status,
        List<ApprovalStepResponse> steps,
        Instant createdAt,
        Instant completedAt
) {
}
