package com.agreemint.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Request body for {@code POST /api/templates/{id}/ai-fix-layout}.
 *
 * <p>Sends a single page's layout JSON together with a list of
 * client-detected geometry / text issues. The model is asked to emit a
 * minimally-corrected page (NOT the full layout) so the editor can swap
 * just that page.
 *
 * <p>{@code page} is the {@code pages[i]} object from the editor's layout
 * JSON — same shape the model sees in the broader generate flow, scoped
 * to one page so the prompt + response stay small.
 *
 * <p>{@code pageSpec} is the page geometry (size, margins) so the model
 * knows the printable area when nudging elements back into bounds.
 */
public record AiFixLayoutRequest(
        JsonNode page,
        JsonNode pageSpec,
        JsonNode variables,
        List<DetectedIssue> issues
) {
    /**
     * One detected issue. {@code kind} is a stable enum-like string the
     * prompt switches on. {@code elementId} identifies the element in
     * the page's elements array; {@code data} carries kind-specific
     * details (overlap depth, overflow pixels, glued-text sample, etc.).
     */
    public record DetectedIssue(
            String kind,
            String elementId,
            JsonNode data
    ) {
    }
}
