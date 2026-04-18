package com.agreemint.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * New-template payload. {@code productId} is required for newly-created
 * templates (introduced in the Products feature); the field is nullable at
 * the transport level so validation can produce a clean 400 with a
 * descriptive message when it's omitted.
 */
public record CreateTemplateRequest(
        @NotBlank @Size(max = 512) String name,
        @Size(max = 256) String createdBy,
        UUID productId
) {
}
