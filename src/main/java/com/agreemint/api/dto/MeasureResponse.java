package com.agreemint.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Response for {@code POST /api/generate/measure}. Maps each requested element's
 * id to its laid-out geometry as iText would render it.
 *
 * <p>For a TEXT element, {@link ElementMeasurement#textLines} holds the per-line
 * y/h plus per-run widths — the frontend replays this by absolutely-positioning
 * each line inside the element box instead of letting CSS flow decide. For a
 * TABLE element, {@link ElementMeasurement#rowHeights} is filled (plus
 * {@code header}/{@code body} cell-by-cell measurements via nested maps once
 * the full measurement is implemented in phase 1).
 *
 * <p>Phase 0 ships this shape but returns empty measurements — the goal is to
 * unblock the frontend integration before the iText measurement pass lands.
 */
public record MeasureResponse(
        Map<String, ElementMeasurement> measurements
) {

    public record ElementMeasurement(
            /** Total height consumed by the laid-out content, in pt. Zero when unmeasured. */
            float measuredHeight,
            /** Per-line geometry for text-bearing elements. Empty for non-text / unmeasured. */
            List<TextLine> textLines,
            /** For TABLE: resolved row heights after cell-content wrap. Empty otherwise. */
            List<Float> rowHeights
    ) {
        public static ElementMeasurement empty() {
            return new ElementMeasurement(0f, List.of(), List.of());
        }
    }

    public record TextLine(
            /** Y offset from the element's top, in pt. */
            float y,
            /** Line-box height, in pt. */
            float h,
            /** Run-level widths within this line, in author-supplied run order. */
            List<RunMeasurement> runs
    ) {
    }

    public record RunMeasurement(
            /** Resolved text after variable substitution. */
            String text,
            /** Advance width in pt (sum of glyph widths + kerning). */
            float width,
            /** Index into the element's content.runs array. */
            int runIndex
    ) {
    }
}
