package com.agreemint.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTemplateRequest(
        @NotBlank @Size(max = 512) String name,
        @Size(max = 256) String createdBy
) {
}
