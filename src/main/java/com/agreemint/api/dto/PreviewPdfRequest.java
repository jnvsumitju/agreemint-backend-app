package com.agreemint.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record PreviewPdfRequest(
        JsonNode layout,
        JsonNode data
) {
}
