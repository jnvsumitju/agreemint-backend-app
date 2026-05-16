package com.agreemint.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Request body for {@code POST /api/templates/{id}/ai-outline}.
 *
 * <p>The outline call asks the model to enumerate the sections it would
 * produce for a long, structured document so the frontend can chunk the
 * generation into multiple smaller calls. Same shape as the generate /
 * clarify requests.
 */
public record AiOutlineRequest(
        String instruction,
        JsonNode currentLayout,
        JsonNode variables
) {
}
