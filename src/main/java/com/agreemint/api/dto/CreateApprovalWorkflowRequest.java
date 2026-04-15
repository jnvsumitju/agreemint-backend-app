package com.agreemint.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CreateApprovalWorkflowRequest(
        @NotNull UUID documentId,
        @NotEmpty List<ApprovalStepRequest> steps
) {
}
