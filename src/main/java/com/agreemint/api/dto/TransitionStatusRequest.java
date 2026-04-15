package com.agreemint.api.dto;

import com.agreemint.domain.LifecycleStatus;
import jakarta.validation.constraints.NotNull;

public record TransitionStatusRequest(
        @NotNull LifecycleStatus targetStatus,
        String comment
) {
}
