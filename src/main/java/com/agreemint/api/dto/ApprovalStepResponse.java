package com.agreemint.api.dto;

import com.agreemint.domain.ApprovalStatus;
import com.agreemint.domain.ApprovalStep;

import java.time.Instant;
import java.util.UUID;

public record ApprovalStepResponse(
        UUID id,
        int stepOrder,
        UUID assigneeId,
        String assigneeName,
        String roleLabel,
        ApprovalStatus status,
        String comment,
        Instant decidedAt
) {
    public static ApprovalStepResponse from(ApprovalStep s) {
        return new ApprovalStepResponse(
                s.getId(),
                s.getStepOrder(),
                s.getAssignee().getId(),
                s.getAssignee().getName(),
                s.getRoleLabel(),
                s.getStatus(),
                s.getComment(),
                s.getDecidedAt()
        );
    }
}
