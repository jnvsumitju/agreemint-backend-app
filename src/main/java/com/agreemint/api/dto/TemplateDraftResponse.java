package com.agreemint.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record TemplateDraftResponse(
        JsonNode layout,
        JsonNode variables,
        Instant updatedAt
) {
}
