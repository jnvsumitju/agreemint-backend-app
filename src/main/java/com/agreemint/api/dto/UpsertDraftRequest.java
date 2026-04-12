package com.agreemint.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record UpsertDraftRequest(
        JsonNode layout,
        JsonNode variables
) {
}
