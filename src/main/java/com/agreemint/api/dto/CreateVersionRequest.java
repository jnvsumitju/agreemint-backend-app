package com.agreemint.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record CreateVersionRequest(
        JsonNode layout,
        JsonNode variables
) {
}
