package com.agreemint.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record TemplateVersionResponse(
        UUID id,
        UUID templateId,
        int versionNumber,
        JsonNode layout,
        JsonNode variables,
        Instant createdAt
) {
}
