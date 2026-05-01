package com.agreemint.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Request body for {@code POST /api/templates/{id}/ai-clarify}.
 *
 * <p>Same shape as {@link AiGenerateRequest}. The clarifier endpoint reuses
 * the layout + variables context to decide whether the instruction is
 * specific enough to generate from, or whether 1–4 follow-up questions
 * would meaningfully improve the result.
 */
public record AiClarifyRequest(
        String instruction,
        JsonNode currentLayout,
        JsonNode variables,
        /** See {@link AiGenerateRequest#targetElementId()}. */
        String targetElementId
) {
}
