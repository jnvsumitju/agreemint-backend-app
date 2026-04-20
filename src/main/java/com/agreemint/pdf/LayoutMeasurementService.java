package com.agreemint.pdf;

import com.agreemint.api.dto.MeasureResponse;
import com.agreemint.api.dto.MeasureResponse.ElementMeasurement;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the iText layout engine without producing PDF bytes, returning the
 * geometry the render pass <em>would</em> have computed. The canvas consumes
 * this output to top-anchor text identically and to soft-block save when a
 * text box overflows its configured height — canvas and PDF agree because
 * there's one measurement authority.
 *
 * <p>Phase 1: implements {@code measuredHeight} for TEXT-like elements via
 * {@link PdfRendererService#measureTextElementHeight}. Per-line geometry
 * ({@code textLines}) arrives in phase 1.5 once the canvas replay path lands.
 * Phase 2 extends this to TABLE / LIST.
 */
@Service
public class LayoutMeasurementService {

    /** Broken out behind a lazy {@link ObjectProvider} so tests can instantiate the service standalone. */
    private final ObjectProvider<PdfRendererService> rendererProvider;

    public LayoutMeasurementService(ObjectProvider<PdfRendererService> rendererProvider) {
        this.rendererProvider = rendererProvider;
    }

    public MeasureResponse measure(JsonNode layout, JsonNode data, List<String> elementIds) {
        Map<String, ElementMeasurement> out = new LinkedHashMap<>();
        if (layout == null || layout.isNull()) {
            return new MeasureResponse(out);
        }

        boolean subset = elementIds != null && !elementIds.isEmpty();
        java.util.Set<String> allowed = subset ? java.util.Set.copyOf(elementIds) : java.util.Set.of();

        PdfRendererService renderer = rendererProvider == null ? null : rendererProvider.getIfAvailable();

        for (JsonNode element : walkElements(layout)) {
            JsonNode idNode = element.get("id");
            if (idNode == null || !idNode.isTextual()) continue;
            String id = idNode.asText();
            if (subset && !allowed.contains(id)) continue;
            out.put(id, measureElement(element, data, renderer));
        }
        return new MeasureResponse(out);
    }

    private ElementMeasurement measureElement(JsonNode element, JsonNode data, PdfRendererService renderer) {
        if (renderer == null) return ElementMeasurement.empty();
        String type = element.path("type").asText(null);
        if (PdfRendererService.isTextLikeType(type)) {
            PdfRendererService.ParagraphLayout layout = renderer.measureTextElementLayout(element, data);
            return new ElementMeasurement(layout.height(), layout.lines(), List.of());
        }
        if ("TABLE".equals(type)) {
            // Header + body row heights the PDF renderer will demand. The
            // canvas consumes these to lock gridTemplateRows so the onscreen
            // rows match the PDF pixel-for-pixel. Summed total > element
            // height → flag as overflow in the save-time soft-assist.
            List<Float> rowHeights = renderer.measureTableRowHeights(element, data);
            if (rowHeights.isEmpty()) return ElementMeasurement.empty();
            float total = 0f;
            for (Float h : rowHeights) total += h;
            return new ElementMeasurement(total, List.of(), rowHeights);
        }
        // LIST / IMAGE / shapes — phase 3+ scope.
        return ElementMeasurement.empty();
    }

    /**
     * Flatten every top-level element AND every band-child across every page.
     * Matches the walk order {@link PdfRendererService} uses when it reaches
     * the text-element rendering path (top-level + {@code renderBandChildren}
     * dispatch), so element ids line up 1:1 between measurement and render.
     *
     * <p>Band children: HEADER/FOOTER elements carry a {@code bandElements}
     * array in band-local coordinates. The measurement endpoint needs to
     * surface measurements under those children's ids too — otherwise a
     * header with an editable TEXT inside is invisible to the overflow check.
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
        // Expand bandElements on any element that carries them. Band children
        // don't nest beyond one level today, so a single flatten suffices.
        java.util.List<JsonNode> withBandChildren = new java.util.ArrayList<>(all);
        for (JsonNode el : all) {
            JsonNode band = el.path("bandElements");
            if (band.isArray() && !band.isEmpty()) {
                Iterator<JsonNode> it = band.elements();
                while (it.hasNext()) withBandChildren.add(it.next());
            }
        }
        return withBandChildren;
    }

    private void appendArray(java.util.List<JsonNode> sink, JsonNode arr) {
        if (!arr.isArray()) return;
        Iterator<JsonNode> it = arr.elements();
        while (it.hasNext()) sink.add(it.next());
    }
}
