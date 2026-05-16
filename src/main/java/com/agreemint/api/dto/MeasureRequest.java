package com.agreemint.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Request body for {@code POST /api/generate/measure}.
 *
 * <p>{@code layout} and {@code data} mirror {@link PreviewPdfRequest} so the
 * measurement endpoint can be fed the exact same inputs as the render endpoint
 * (required — measurement is "layout as iText would lay it out, without writing
 * bytes"). {@code elementIds} is an optional subset: when non-empty, only the
 * listed top-level elements are measured. Used by the canvas to debounce —
 * after a single-element edit, we remeasure just that element instead of the
 * whole page.
 */
public record MeasureRequest(
        JsonNode layout,
        JsonNode data,
        List<String> elementIds
) {
}
