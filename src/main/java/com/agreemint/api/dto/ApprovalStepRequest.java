package com.agreemint.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ApprovalStepRequest(
        @NotNull UUID assigneeId,
        String roleLabel
) {
}
