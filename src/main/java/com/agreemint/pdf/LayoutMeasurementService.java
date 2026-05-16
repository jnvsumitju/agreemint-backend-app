package com.agreemint.pdf;

import com.agreemint.api.dto.MeasureResponse;
import com.agreemint.api.dto.MeasureResponse.ElementMeasurement;
import com.agreemint.api.dto.TextReflowResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    // ── Text reflow (for linked text frames) ───────────────────────────────
    //
    // The frontend used to make this decision in the browser using DOM
    // measurement, but browser glyph metrics drift from iText's — so the
    // editor's split could land at a paragraph the PDF wouldn't have, leaving
    // hidden content stuck inside the head frame's clamped height. Doing the
    // reflow here means the split point is whatever iText would have picked
    // when rendering, so the editor preview and the final PDF agree.

    private static final ObjectMapper REFLOW_MAPPER = new ObjectMapper();

    /**
     * Decide how the head element's content should be split across linked
     * frames. Returns one frame per page region until the content is consumed.
     * Always returns at least one frame; an unmeasurable element (zero width,
     * empty content, etc.) returns a single frame with the content unchanged.
     */
    public TextReflowResponse reflow(JsonNode headElement, JsonNode pageSpec, JsonNode data) {
        List<TextReflowResponse.Frame> frames = new ArrayList<>();
        if (headElement == null || headElement.isNull()) {
            return new TextReflowResponse(frames);
        }
        PdfRendererService renderer = rendererProvider == null ? null : rendererProvider.getIfAvailable();
        if (renderer == null) {
            return new TextReflowResponse(frames);
        }
        float width = (float) headElement.path("width").asDouble(0);
        float headY = (float) headElement.path("y").asDouble(0);
        if (width <= 0f) {
            return singleFallbackFrame(headElement);
        }

        // Page geometry: head frame fits between its top edge and the bottom
        // margin; continuation frames fit between top and bottom margins.
        float pageHeight = resolvePageHeight(pageSpec);
        Margins m = resolveMargins(pageSpec);
        float headMaxH = pageHeight - m.bottom() - headY;
        float contMaxH = pageHeight - m.bottom() - m.top();
        if (headMaxH <= 0f || contMaxH <= 0f) {
            return singleFallbackFrame(headElement);
        }

        List<JsonNode> paragraphs = splitContentIntoParagraphs(headElement.get("content"));
        if (paragraphs.isEmpty()) {
            return singleFallbackFrame(headElement);
        }

        JsonNode style = headElement.path("style");

        int idx = 0;
        while (idx < paragraphs.size()) {
            boolean isHead = frames.isEmpty();
            float maxH = isHead ? headMaxH : contMaxH;

            // Linear walk from the front of the remaining paragraphs:
            // accumulate as many as iText says still fit. Always keep at
            // least one — a paragraph taller than maxH on its own still has
            // to land somewhere, and splitting mid-paragraph isn't supported.
            int count = 1;
            float lastFittingHeight = 0f;
            String lastFittingContent = serializeParagraphs(paragraphs.subList(idx, idx + count));
            while (true) {
                float h = measureCandidate(renderer, headElement, style, width, lastFittingContent, data);
                if (h > maxH && count > 1) {
                    // Roll back: previous count was the largest that fit.
                    count--;
                    lastFittingContent = serializeParagraphs(paragraphs.subList(idx, idx + count));
                    lastFittingHeight = measureCandidate(renderer, headElement, style, width, lastFittingContent, data);
                    break;
                }
                lastFittingHeight = h;
                if (idx + count >= paragraphs.size()) break;
                count++;
                lastFittingContent = serializeParagraphs(paragraphs.subList(idx, idx + count));
            }

            frames.add(new TextReflowResponse.Frame(
                    lastFittingContent,
                    lastFittingHeight,
                    idx,
                    idx + count));
            idx += count;
        }
        return new TextReflowResponse(frames);
    }

    /** Single-frame fallback — content unchanged, height best-effort. */
    private TextReflowResponse singleFallbackFrame(JsonNode headElement) {
        JsonNode content = headElement.path("content");
        String s = content.isTextual() ? content.asText("") : (content.isObject() ? content.toString() : "");
        return new TextReflowResponse(List.of(
                new TextReflowResponse.Frame(s, 0f, 0, 1)
        ));
    }

    /**
     * Build a temp element JSON that mirrors the head's style/width but with
     * candidate {@code content}, then ask iText for its rendered height.
     */
    private float measureCandidate(PdfRendererService renderer, JsonNode headElement,
                                   JsonNode style, float width, String content, JsonNode data) {
        ObjectNode tmp = REFLOW_MAPPER.createObjectNode();
        tmp.put("type", headElement.path("type").asText("TEXT"));
        tmp.put("width", width);
        if (style != null && !style.isMissingNode()) tmp.set("style", style);
        tmp.put("content", content);
        return renderer.measureTextElementHeight(tmp, data);
    }

    /** Page height in pt from a {@code pageSpec} JSON node. */
    private float resolvePageHeight(JsonNode pageSpec) {
        if (pageSpec == null || pageSpec.isMissingNode() || pageSpec.isNull()) return 842f; // A4 default
        // Explicit height wins.
        float h = (float) pageSpec.path("height").asDouble(0);
        if (h > 0f) return h;
        String size = pageSpec.path("size").asText("A4").toUpperCase(java.util.Locale.ROOT);
        return switch (size) {
            case "LETTER" -> 792f;
            case "LEGAL" -> 1008f;
            case "TABLOID" -> 1224f;
            case "EXECUTIVE" -> 756f;
            case "A3" -> 1191f;
            case "A5" -> 595f;
            case "B4" -> 1000f;
            case "B5" -> 708f;
            default -> 842f;
        };
    }

    private Margins resolveMargins(JsonNode pageSpec) {
        float uniform = pageSpec == null ? 40f : (float) pageSpec.path("margin").asDouble(40);
        JsonNode m = pageSpec == null ? null : pageSpec.path("margins");
        if (m != null && m.isObject()) {
            return new Margins(
                    (float) m.path("top").asDouble(uniform),
                    (float) m.path("right").asDouble(uniform),
                    (float) m.path("bottom").asDouble(uniform),
                    (float) m.path("left").asDouble(uniform));
        }
        return new Margins(uniform, uniform, uniform, uniform);
    }

    private record Margins(float top, float right, float bottom, float left) {}

    /**
     * Split a rich content field into per-paragraph rich-runs arrays. Mirrors
     * the frontend's {@code splitContentIntoParagraphs}: variable runs stay
     * with their current paragraph; text runs split on {@code \n}.
     */
    private List<JsonNode> splitContentIntoParagraphs(JsonNode contentField) {
        ArrayNode runs = readRichRuns(contentField);
        if (runs == null || runs.size() == 0) return List.of();

        List<ArrayNode> groups = new ArrayList<>();
        groups.add(REFLOW_MAPPER.createArrayNode());
        for (JsonNode run : runs) {
            String type = run.path("type").asText("text");
            if ("var".equals(type)) {
                groups.get(groups.size() - 1).add(run);
                continue;
            }
            String text = run.path("text").asText("");
            String[] parts = text.split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) groups.add(REFLOW_MAPPER.createArrayNode());
                if (!parts[i].isEmpty()) {
                    ObjectNode clone = run.deepCopy();
                    clone.put("text", parts[i]);
                    groups.get(groups.size() - 1).add(clone);
                }
            }
        }

        List<JsonNode> out = new ArrayList<>(groups.size());
        for (ArrayNode g : groups) {
            if (g.size() == 0) {
                ArrayNode empty = REFLOW_MAPPER.createArrayNode();
                ObjectNode emptyRun = REFLOW_MAPPER.createObjectNode();
                emptyRun.put("type", "text");
                emptyRun.put("text", "");
                empty.add(emptyRun);
                out.add(empty);
            } else {
                out.add(g);
            }
        }
        return out;
    }

    /**
     * Serialize a slice of paragraphs back into the rich-content JSON wire
     * shape the frontend persists ({@code {"rich":true,"runs":[...]}}), with
     * a literal {@code \n} text run inserted between paragraphs.
     */
    private String serializeParagraphs(List<JsonNode> paragraphs) {
        ArrayNode allRuns = REFLOW_MAPPER.createArrayNode();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (i > 0) {
                ObjectNode br = REFLOW_MAPPER.createObjectNode();
                br.put("type", "text");
                br.put("text", "\n");
                allRuns.add(br);
            }
            JsonNode group = paragraphs.get(i);
            for (JsonNode run : group) {
                allRuns.add(run);
            }
        }
        ObjectNode root = REFLOW_MAPPER.createObjectNode();
        root.put("rich", true);
        root.set("runs", allRuns);
        return root.toString();
    }

    /**
     * Coerce a content field into its rich-runs array. Accepts either the
     * already-parsed object form ({@code {rich:true, runs:[...]}}) or the
     * stringified JSON the canvas persists in {@code element.content}.
     */
    private ArrayNode readRichRuns(JsonNode contentField) {
        if (contentField == null || contentField.isNull() || contentField.isMissingNode()) return null;
        JsonNode obj = contentField;
        if (contentField.isTextual()) {
            String s = contentField.asText("");
            if (s.isEmpty() || !s.trim().startsWith("{")) return null;
            try {
                obj = REFLOW_MAPPER.readTree(s);
            } catch (Exception e) {
                return null;
            }
        }
        if (!obj.isObject()) return null;
        if (!obj.path("rich").asBoolean(false)) return null;
        JsonNode runs = obj.path("runs");
        if (!runs.isArray()) return null;
        return (ArrayNode) runs;
    }
}
