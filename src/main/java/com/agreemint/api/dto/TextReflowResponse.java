package com.agreemint.api.dto;

import java.util.List;

/**
 * Response for {@code POST /api/generate/measure/reflow}. Each {@link Frame}
 * is one chunk of the original content sized to fit its target page region —
 * the first frame fits between the head element's top edge and the bottom
 * margin; subsequent frames fit between top and bottom margins.
 *
 * <p>Frontend consumes this by replacing the head element's content with
 * frames[0] and creating linked continuation elements for frames[1..N] on
 * subsequent pages (creating new pages as needed).
 */
public record TextReflowResponse(
        List<Frame> frames
) {

    public record Frame(
            /** Rich content JSON ({@code {"rich":true,"runs":[...]}}) for this frame. */
            String content,
            /** Total height this frame's content occupies, in pt, as iText measured it. */
            float measuredHeight,
            /** Inclusive start index of paragraphs from the original split. */
            int paragraphStart,
            /** Exclusive end index of paragraphs from the original split. */
            int paragraphEnd
    ) {
    }
}
