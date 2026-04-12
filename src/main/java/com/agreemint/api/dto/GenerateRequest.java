package com.agreemint.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record GenerateRequest(
        @NotNull UUID templateId,
        @NotNull UUID versionId,
        JsonNode data
) {
}
