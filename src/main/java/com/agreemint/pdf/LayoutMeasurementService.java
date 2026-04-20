package com.agreemint.pdf;

import com.agreemint.api.dto.MeasureResponse;
import com.agreemint.api.dto.MeasureResponse.ElementMeasurement;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the iText layout engine without producing PDF bytes, returning the
 * geometry the render pass <em>would</em> have computed. The canvas consumes
 * this output to absolutely-position each text line — so canvas and PDF flow
 * through a single measurement authority rather than measuring in parallel
 * and hoping CSS matches iText.
 *
 * <p>Phase 0: stub. Iterates the layout, collects element ids, returns an
 * empty measurement per id. The endpoint is live so the frontend can wire the
 * client-side integration against a real contract. Phase 1 replaces the stub
 * with an actual {@code Canvas}/{@code ParagraphRenderer}-based layout pass
 * that reuses {@link PdfFontRegistry} and the same styling code as
 * {@link PdfRendererService}.
 */
@Service
public class LayoutMeasurementService {

    public MeasureResponse measure(JsonNode layout, JsonNode data, List<String> elementIds) {
        Map<String, ElementMeasurement> out = new LinkedHashMap<>();
        if (layout == null || layout.isNull()) {
            return new MeasureResponse(out);
        }

        boolean subset = elementIds != null && !elementIds.isEmpty();
        java.util.Set<String> allowed = subset ? java.util.Set.copyOf(elementIds) : java.util.Set.of();

        for (JsonNode element : walkElements(layout)) {
            JsonNode idNode = element.get("id");
            if (idNode == null || !idNode.isTextual()) continue;
            String id = idNode.asText();
            if (subset && !allowed.contains(id)) continue;
            out.put(id, ElementMeasurement.empty());
        }
        return new MeasureResponse(out);
    }

    /**
     * Flatten every top-level element across every page. Matches the walk
     * order {@link PdfRendererService} uses so element ids line up 1:1 with
     * what the PDF renderer sees.
     */
    private Iterable<JsonNode> walkElements(JsonNode layout) {
        java.util.List<JsonNode> all = new java.util.ArrayList<>();
        JsonNode pages = layout.path("pages");
        if (pages.isArray() && !pages.isEmpty()) {
            for (JsonNode page : pages) {
                appendArray(all, page.path("elements"));
            }
        } else {
            appendArray(all, layout.path("elements"));
        }
        return all;
    }

    private void appendArray(java.util.List<JsonNode> sink, JsonNode arr) {
        if (!arr.isArray()) return;
        Iterator<JsonNode> it = arr.elements();
        while (it.hasNext()) sink.add(it.next());
    }
}
