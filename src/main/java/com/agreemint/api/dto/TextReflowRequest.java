package com.agreemint.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Request body for {@code POST /api/generate/measure/reflow}.
 *
 * <p>Asks the backend to compute how a TEXT element's content should be split
 * across linked frames given the page geometry. The split decision is made by
 * the same iText layout engine that renders the PDF, so the editor preview
 * matches what the author will see in the final document.
 *
 * <p>{@code headElement} carries the element's id, x, y, width, content, and
 * style — everything iText needs to lay out a paragraph at the head element's
 * position. {@code pageSpec} mirrors the canvas page configuration so we can
 * compute the available height between the head and the bottom margin (and
 * between margins for continuation frames). {@code data} is optional and
 * supports variable resolution when the content contains {@code {{var}}}
 * chips — without it variables render as their literal name.
 */
public record TextReflowRequest(
        JsonNode headElement,
        JsonNode pageSpec,
        JsonNode data
) {
}
