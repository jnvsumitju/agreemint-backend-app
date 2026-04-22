package com.agreemint.pdf;

import com.agreemint.api.dto.MeasureResponse;
import com.agreemint.config.PixelParityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.io.image.ImageData;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.PdfArray;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.action.PdfAction;
import com.itextpdf.kernel.pdf.annot.PdfLinkAnnotation;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Div;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Link;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.OverflowPropertyValue;
import com.itextpdf.layout.properties.Property;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import com.itextpdf.layout.layout.LayoutArea;
import com.itextpdf.layout.layout.LayoutContext;
import com.itextpdf.layout.layout.LayoutResult;
import com.itextpdf.layout.renderer.CellRenderer;
import com.itextpdf.layout.renderer.DrawContext;
import com.itextpdf.layout.renderer.IRenderer;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders layout DSL to PDF. Coordinates use top-left origin, Y increasing downward (same as canvas UI).
 * Converted to iText bottom-left internally.
 */
@Service
public class PdfRendererService {

    private static final Logger log = LoggerFactory.getLogger(PdfRendererService.class);

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");

    /** Built-in merge keys; overlay per PDF page so headers/footers can show page x of y. */
    private static final String DATA_KEY_TOTAL_PAGES = "totalPages";
    private static final String DATA_KEY_PAGE_NUMBER = "pageNumber";
    private static final String DATA_KEY_CURRENT_DATE = "currentDate";

    /** Canvas default — matches {@code RichTextBlockPreview.tsx:63} (`lineHeight: lineHeight ?? 1.4`). */
    static final float DEFAULT_LINE_HEIGHT = 1.4f;

    /** Default sans family used when the parity flag is on and the element/run doesn't set one. */
    private static final String DEFAULT_PARITY_FAMILY = PdfFontRegistry.FAMILY_SANS;

    private final ObjectMapper objectMapper;
    private final LayoutBehaviourResolver behaviourResolver;
    private final PdfFontRegistry fontRegistry;
    private final PixelParityProperties pixelParity;

    public PdfRendererService(ObjectMapper objectMapper,
                              LayoutBehaviourResolver behaviourResolver,
                              PdfFontRegistry fontRegistry,
                              PixelParityProperties pixelParity) {
        this.objectMapper = objectMapper;
        this.behaviourResolver = behaviourResolver;
        this.fontRegistry = fontRegistry;
        this.pixelParity = pixelParity;
    }

    /** True when pixel-parity rendering should be used for this render pass. */
    private boolean parityOn() {
        return pixelParity != null && pixelParity.isEnabled() && fontRegistry != null && fontRegistry.isFullyLoaded();
    }

    /**
     * Resolve the font for an element's style plus optional run override. Returns
     * null when parity is off or the registry can't satisfy the request — callers
     * then fall back to iText's default Helvetica and the legacy
     * {@code setBold()}/{@code setItalic()} synthetic pair.
     */
    private PdfFont resolveParityFont(JsonNode elementStyle, boolean bold, boolean italic) {
        if (!parityOn()) return null;
        String family = elementStyle == null ? null : elementStyle.path("fontFamily").asText("");
        if (family == null || family.isBlank()) family = DEFAULT_PARITY_FAMILY;
        // Legacy layouts may carry a non-parity family ("Arial", "Roboto"). The
        // registry falls back to Inter for those — matching the coercion the
        // frontend does via `coerceToSupportedFamily` so canvas and PDF agree.
        return fontRegistry.createFont(family, bold, italic);
    }

    public byte[] render(JsonNode layoutJson, JsonNode data) throws IOException {
        long startNanos = System.nanoTime();
        PageSpec pageSpec = readPage(layoutJson);
        List<JsonNode> perPageElements = pageElementArraysFromLayout(layoutJson);
        int totalElements = perPageElements.stream().mapToInt(p -> p.isArray() ? p.size() : 0).sum();
        log.info("render start parity={} pages={} elements={}",
                parityOn(), perPageElements.size(), totalElements);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);
        PageSize pageSize = pageSpec.pageSize();
        /*
         * layout.Document does not create a physical page until content is added. Code paths such as
         * addText (background fill) use PdfCanvas on page 1 first — ensure the page exists.
         */
        pdfDoc.addNewPage(pageSize);
        Document document = new Document(pdfDoc, pageSize);
        /*
         * Canvas stores element x,y from the top-left of the full page (same pt system as PDF page size).
         * Fixed layout uses absolute page coordinates — do not offset by document margins here.
         */
        document.setMargins(0, 0, 0, 0);

        float pageHeight = pageSize.getHeight();
        int totalPages = Math.max(1, perPageElements.size());

        for (int pageIdx = 0; pageIdx < perPageElements.size(); pageIdx++) {
            int pageNumber = pageIdx + 1;
            if (pageIdx > 0) {
                pdfDoc.addNewPage(pageSize);
            }
            JsonNode pageData = dataWithBuiltinPageVars(data, pageNumber, totalPages);
            List<JsonNode> elements = mergedElementsForPdfPage(perPageElements, pageIdx);
            for (JsonNode el : elements) {
                LayoutBehaviourResolver.Resolution resolved = behaviourResolver.resolveElement(el, pageData, null);
                if (!resolved.visible()) {
                    continue;
                }
                JsonNode drawEl = resolved.element();
                String type = drawEl.path("type").asText("TEXT").toUpperCase(Locale.ROOT);
                // Per-element try/catch so one bad element (unsupported
                // font glyph, malformed link, bogus image URL, …) can't
                // tank the whole page. Log the offender and move on.
                // Symptom this prevents: element N throwing silently
                // stopped the loop, elements N+1..end never rendered.
                try {
                    if (("HEADER".equals(type) || "FOOTER".equals(type))
                            && drawEl.path("bandElements").isArray()
                            && drawEl.path("bandElements").size() > 0) {
                        renderBandChildren(pdfDoc, document, drawEl, pageData, pageHeight, pageNumber);
                        continue;
                    }
                    dispatchElementByType(pdfDoc, document, drawEl, type, pageData, pageHeight, pageNumber);
                } catch (RuntimeException | IOException ex) {
                    log.warn("PDF render skipped element id={} type={} ({}): {}",
                            drawEl.path("id").asText("?"), type,
                            ex.getClass().getSimpleName(), ex.getMessage());
                }
            }
        }

        document.close();
        byte[] bytes = baos.toByteArray();
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info("render done pages={} bytes={} elapsedMs={}",
                perPageElements.size(), bytes.length, ms);
        return bytes;
    }

    /**
     * Prefer {@code pages[].elements} when {@code pages} is a non-empty array (multi-page editor export);
     * otherwise use root {@code elements} (legacy single-page).
     */
    static List<JsonNode> pageElementArraysFromLayout(JsonNode layoutJson) {
        JsonNode pages = layoutJson.path("pages");
        if (pages.isArray() && !pages.isEmpty()) {
            List<JsonNode> out = new ArrayList<>(pages.size());
            for (JsonNode p : pages) {
                JsonNode els = p.path("elements");
                out.add(els.isArray() ? els : JsonNodeFactory.instance.arrayNode());
            }
            return out;
        }
        JsonNode elements = layoutJson.path("elements");
        if (!elements.isArray()) {
            return List.of(JsonNodeFactory.instance.arrayNode());
        }
        return List.of(elements);
    }

    /**
     * Multi-page layouts: repeat page 0's HEADER/FOOTER on every physical page (same as editor merge).
     * Strips HEADER/FOOTER from pages after the first so bands are not duplicated if present in JSON.
     */
    static List<JsonNode> mergedElementsForPdfPage(List<JsonNode> perPageElements, int pageIndex) {
        if (pageIndex < 0 || pageIndex >= perPageElements.size()) {
            return List.of();
        }
        JsonNode pageEls = perPageElements.get(pageIndex);
        if (perPageElements.size() <= 1 || pageIndex == 0) {
            return jsonArrayToList(pageEls);
        }
        List<JsonNode> bands = headerFooterNodesFromPageElements(perPageElements.get(0));
        List<JsonNode> body = filterOutHeaderFooter(jsonArrayToList(pageEls));
        if (bands.isEmpty()) {
            return body;
        }
        List<JsonNode> merged = new ArrayList<>(bands.size() + body.size());
        merged.addAll(bands);
        merged.addAll(body);
        return merged;
    }

    private static List<JsonNode> jsonArrayToList(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<JsonNode> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            out.add(n);
        }
        return out;
    }

    private static List<JsonNode> headerFooterNodesFromPageElements(JsonNode page0elements) {
        if (page0elements == null || !page0elements.isArray()) {
            return List.of();
        }
        List<JsonNode> bands = new ArrayList<>();
        for (JsonNode el : page0elements) {
            String type = el.path("type").asText("TEXT").toUpperCase(Locale.ROOT);
            if ("HEADER".equals(type) || "FOOTER".equals(type)) {
                bands.add(el);
            }
        }
        return bands.isEmpty() ? List.of() : bands;
    }

    private static List<JsonNode> filterOutHeaderFooter(List<JsonNode> elements) {
        if (elements.isEmpty()) {
            return elements;
        }
        List<JsonNode> out = new ArrayList<>(elements.size());
        for (JsonNode el : elements) {
            String type = el.path("type").asText("TEXT").toUpperCase(Locale.ROOT);
            if (!"HEADER".equals(type) && !"FOOTER".equals(type)) {
                out.add(el);
            }
        }
        return out;
    }

    /**
     * Ensures {@code totalPages} and {@code pageNumber} exist on the data root for each physical page
     * (matches editor behaviour). Caller JSON may supply these; per-page values win.
     */
    private JsonNode dataWithBuiltinPageVars(JsonNode data, int pageNumber1Based, int totalPages) {
        ObjectNode base;
        if (data != null && data.isObject()) {
            base = (ObjectNode) data.deepCopy();
        } else {
            base = objectMapper.createObjectNode();
        }
        base.put(DATA_KEY_TOTAL_PAGES, Integer.toString(totalPages));
        base.put(DATA_KEY_PAGE_NUMBER, Integer.toString(pageNumber1Based));
        // Auto-populate `currentDate` (ISO `YYYY-MM-DD`). The frontend strips
        // this key from the payload on its way out (it's a system variable
        // the comment said "backend computes"), but nothing actually did —
        // so a TEXT element with a {{currentDate}} chip rendered empty in
        // the generated PDF. Seed only if the caller didn't already provide
        // one (API callers may want to override with their own date).
        if (!base.has(DATA_KEY_CURRENT_DATE)) {
            base.put(DATA_KEY_CURRENT_DATE, java.time.LocalDate.now().toString());
        }
        return base;
    }

    /** Draw HEADER/FOOTER composed of {@code bandElements} (band-local coords → page coords). */
    private void renderBandChildren(
            PdfDocument pdfDoc,
            Document document,
            JsonNode band,
            JsonNode pageData,
            float pageHeight,
            int pageNumber) throws IOException {
        double bx = band.path("x").asDouble(0);
        double by = band.path("y").asDouble(0);
        for (JsonNode raw : band.get("bandElements")) {
            ObjectNode child = (ObjectNode) raw.deepCopy();
            child.put("x", child.path("x").asDouble(0) + bx);
            child.put("y", child.path("y").asDouble(0) + by);
            LayoutBehaviourResolver.Resolution cr = behaviourResolver.resolveElement(child, pageData, null);
            if (!cr.visible()) {
                continue;
            }
            JsonNode c = cr.element();
            String ct = c.path("type").asText("TEXT").toUpperCase(Locale.ROOT);
            dispatchElementByType(pdfDoc, document, c, ct, pageData, pageHeight, pageNumber);
        }
    }

    private void dispatchElementByType(
            PdfDocument pdfDoc,
            Document document,
            JsonNode drawEl,
            String type,
            JsonNode pageData,
            float pageHeight,
            int pageNumber) throws IOException {
        String id = drawEl.path("id").asText("?");
        try {
            log.debug("render page={} type={} id={} box=({},{},{}×{}) parity={}",
                    pageNumber, type, id,
                    drawEl.path("x").asDouble(0),
                    drawEl.path("y").asDouble(0),
                    drawEl.path("width").asDouble(0),
                    drawEl.path("height").asDouble(0),
                    parityOn());
            switch (type) {
                case "TEXT", "PARAGRAPH", "HEADER", "FOOTER" ->
                        addText(pdfDoc, document, drawEl, pageData, pageHeight, pageNumber);
                case "TABLE" -> addTable(document, drawEl, pageData, pageHeight, pageNumber);
                case "IMAGE" -> addImage(pdfDoc, document, drawEl, pageHeight, pageNumber);
                case "LINE", "DIVIDER" -> addLine(pdfDoc, drawEl, pageHeight, pageNumber);
                case "BOX" -> addBox(pdfDoc, drawEl, pageHeight, pageNumber);
                case "ELLIPSE" -> addEllipseShape(pdfDoc, drawEl, pageHeight, pageNumber);
                case "RING" -> addRingShape(pdfDoc, drawEl, pageHeight, pageNumber);
                case "TRIANGLE" -> addTriangleShape(pdfDoc, drawEl, pageHeight, pageNumber);
                case "DIAMOND" -> addDiamondShape(pdfDoc, drawEl, pageHeight, pageNumber);
                case "STAR" -> addStarShape(pdfDoc, drawEl, pageHeight, pageNumber);
                case "ARROW" -> addArrowShape(pdfDoc, drawEl, pageHeight, pageNumber);
                case "MERGED_SHAPE" -> addMergedShape(pdfDoc, drawEl, pageHeight, pageNumber);
                case "LIST" -> addList(pdfDoc, document, drawEl, pageData, pageHeight, pageNumber);
                default -> addText(pdfDoc, document, drawEl, pageData, pageHeight, pageNumber);
            }
        } catch (RuntimeException e) {
            // Log the failing element's context then rethrow so the exception
            // handler still maps to 500 with its generic message — but ops now
            // sees WHICH element crashed, not just the stack trace.
            log.error("dispatchElementByType failed — type={} id={} page={}: {}",
                    type, id, pageNumber, e.toString(), e);
            throw e;
        }
    }

    private void addText(PdfDocument pdfDoc, Document document, JsonNode el, JsonNode data, float pageHeight, int pageNumber) {
        float x = (float) el.path("x").asDouble(0);
        float elY = (float) el.path("y").asDouble(0);
        float w = (float) el.path("width").asDouble(200);
        float h = (float) el.path("height").asDouble(20);
        float yTop = pageHeight - elY;
        float bottom = yTop - h;

        JsonNode style = el.path("style");

        // Drop shadow rendered as a rasterised PNG overlay UNDER the element,
        // matching {@link #addBox}. Runs before the frame fill + text draw so
        // the shadow lives beneath the textbox. Without this the canvas
        // showed a shadow (CSS drop-shadow on the wrapper) that silently
        // vanished in the generated PDF.
        JsonNode shadowNode = style.path("shadow");
        if (parityOn() && shadowNode.isObject()) {
            paintShadowUnder(pdfDoc, shadowNode,
                    ShadowRasterizer.Shape.RECTANGLE,
                    0f, x, bottom, w, h, pageNumber);
        }

        /*
         * Paragraph backgrounds in iText only cover the laid-out text height, which is usually
         * smaller than the editor's element height — leaving empty space at the top of the box.
         * Paint the frame fill on the canvas to match the canvas (full width × height).
         */
        Color frameBg = parseCssColorToItext(style.path("backgroundColor").asText(""));
        if (frameBg != null) {
            PdfCanvas bgCanvas = new PdfCanvas(pdfDoc.getPage(pageNumber));
            bgCanvas.saveState();
            bgCanvas.setFillColor(frameBg);
            bgCanvas.rectangle(x, bottom, w, h);
            bgCanvas.fill();
            bgCanvas.restoreState();
        }

        Paragraph p = buildParagraphFromContent(el.get("content"), data, null, style);
        p.setPadding(0);
        p.setMargin(0);

        if (parityOn()) {
            // Pixel-parity path. Top-anchor the paragraph via its measured
            // height, then apply a graphics-state clip at the authored box so
            // overflow is hidden on both sides (matches canvas
            // `overflow-hidden`). We DELIBERATELY avoid `Div.setHeight(h)` +
            // `OVERFLOW_HIDDEN` here: iText's BlockRenderer drops ENTIRE
            // content (not just the overflowing portion) when the rendered
            // line-box is even 1pt taller than the configured height, logging
            // "Element content was clipped because some height properties are
            // set" and emitting an empty PDF. The graphics-state clip avoids
            // that cliff — short text renders fully, long text is clipped at
            // the box edge.
            float measured = measureParagraphLayout(p, w, null).height();
            float anchorBottom = measured > 0f ? yTop - measured : bottom;

            // Phase 4.7 — TEXT rotation around the element center. Offset the
            // anchor so the rotated bbox's center stays at the element center.
            float rotatedAnchorX = x;
            float rotatedAnchorY = anchorBottom;
            double rotationDeg = style.path("rotation").asDouble(0);
            if (rotationDeg != 0) {
                double theta = Math.toRadians(-rotationDeg);
                float cos = (float) Math.cos(theta);
                float sin = (float) Math.sin(theta);
                float centerShiftX = w / 2f * (1f - cos) + h / 2f * sin;
                float centerShiftY = h / 2f * (1f - cos) - w / 2f * sin;
                rotatedAnchorX += centerShiftX;
                rotatedAnchorY += centerShiftY;
                p.setProperty(Property.ROTATION_ANGLE, theta);
            }
            applyOpacityToElement(p, style);

            // Push the clip onto the page graphics state BEFORE `document.add`
            // so the paragraph renders through it. Paragraph overflow below
            // `bottom` (or above `yTop`, if rotation swings it) is invisibly
            // masked. Pop the clip after.
            PdfCanvas clipCanvas = new PdfCanvas(pdfDoc.getPage(pageNumber));
            clipCanvas.saveState();
            clipCanvas.rectangle(x, bottom, w, h);
            clipCanvas.clip();
            clipCanvas.endPath();
            p.setFixedPosition(pageNumber, rotatedAnchorX, rotatedAnchorY, w);
            document.add(p);
            clipCanvas.restoreState();

            log.debug("addText parity id={} box=({}, {}, {}×{}) measured={} clipped={}",
                    el.path("id").asText("?"), x, elY, w, h, measured, measured > h);
            return;
        }

        // Legacy bottom-anchor path (flag off). Paragraph grows upward from the
        // element bottom — the historical behaviour that predates phase 1.
        applyOpacityToElement(p, style);
        p.setFixedPosition(pageNumber, x, bottom, w);
        document.add(p);
    }

    private Paragraph buildParagraphFromContent(JsonNode contentField, JsonNode data, JsonNode rowContext, JsonNode elementStyle) {
        return buildParagraphFromContent(contentField, data, rowContext, elementStyle, null);
    }

    /**
     * Variant used by the measurement pass. When {@code runIndexOut} is non-null
     * we record every {@link Text} (and its {@link Link} subclass) created for
     * a rich-run paragraph, keyed by identity, so the per-line walker can
     * recover authored run ordinals from the rendered tree. Non-rich plain
     * strings have no runs to record — the map stays empty for those.
     */
    private Paragraph buildParagraphFromContent(JsonNode contentField, JsonNode data, JsonNode rowContext,
                                                JsonNode elementStyle,
                                                java.util.Map<Text, Integer> runIndexOut) {
        JsonNode runs = resolveRichRuns(contentField);
        if (runs != null) {
            return paragraphFromRuns(runs, data, rowContext, elementStyle, runIndexOut);
        }
        String raw = contentField == null || contentField.isNull() ? "" : contentField.asText("");
        Paragraph p = new Paragraph(substitute(raw, data, rowContext));
        applyTextStyle(p, elementStyle);
        return p;
    }

    private JsonNode resolveRichRuns(JsonNode contentField) {
        if (contentField == null || contentField.isNull()) {
            return null;
        }
        if (contentField.isObject()
                && contentField.path("rich").asBoolean(false)
                && contentField.path("runs").isArray()) {
            return contentField.get("runs");
        }
        if (contentField.isTextual()) {
            String s = contentField.asText();
            if (s.trim().startsWith("{")) {
                try {
                    JsonNode tree = objectMapper.readTree(s);
                    if (tree.path("rich").asBoolean(false) && tree.path("runs").isArray()) {
                        return tree.get("runs");
                    }
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private Paragraph paragraphFromRuns(JsonNode runs, JsonNode data, JsonNode rowContext, JsonNode elementStyle) {
        return paragraphFromRuns(runs, data, rowContext, elementStyle, null);
    }

    private Paragraph paragraphFromRuns(JsonNode runs, JsonNode data, JsonNode rowContext, JsonNode elementStyle,
                                        java.util.Map<Text, Integer> runIndexOut) {
        Paragraph p = new Paragraph();
        if (elementStyle != null && !elementStyle.isNull()) {
            float fs = (float) elementStyle.path("fontSize").asDouble(12);
            p.setFontSize(fs);
            Color baseColor = parseCssColorToItext(elementStyle.path("color").asText(""));
            if (baseColor != null) {
                p.setFontColor(baseColor);
            }
        }
        int runOrdinal = -1;
        for (JsonNode run : runs) {
            runOrdinal++;
            String type = run.path("type").asText("text");
            // Resolve the hyperlink href for this run, if any. `{{var}}`
            // placeholders in the stored href (e.g.
            // "https://portal/{{orderId}}") are substituted against the
            // current row / global data context, then the result is checked
            // against the protocol safe-list. Anything that fails the
            // safe-list is silently dropped so an unsafe URL never makes
            // it into a rendered PDF annotation.
            String rawHref = run.path("linkHref").asText("");
            String resolvedHref = rawHref.isEmpty()
                    ? null
                    : sanitizePdfLinkHref(substitute(rawHref, data, rowContext));

            String textValue;
            if ("var".equals(type)) {
                String name = run.path("name").asText("");
                textValue = lookup(name, data, rowContext);
            } else {
                textValue = substitute(run.path("text").asText(""), data, rowContext);
            }
            Text t;
            if (resolvedHref != null) {
                // iText `Link` extends `Text` and attaches a
                // `PdfLinkAnnotation` covering the run's text box. PDF
                // viewers (Acrobat, Preview, Chrome, Firefox) turn the
                // cursor into a hand / anchor pointer when hovering any
                // area backed by a link annotation — so just creating the
                // Link is enough for the "clickable link with pointer
                // cursor" behaviour the user is asking about.
                Link link = new Link(textValue, PdfAction.createURI(resolvedHref));
                // By default iText's PdfLinkAnnotation ships with a thin
                // black border drawn around the clickable rectangle in
                // many viewers. Zero the border + switch the highlight
                // mode to "invert" so the only visible cue is the text
                // styling below (blue + underline) — same convention web
                // links use.
                PdfLinkAnnotation annot = link.getLinkAnnotation();
                annot.setBorder(new PdfArray(new int[] { 0, 0, 0 }));
                annot.put(PdfName.H, PdfName.I);
                t = link;
                // Visual affordance: if the author didn't set an explicit
                // colour on the run OR the element, paint the linked text
                // in conventional link-blue + give it an underline so the
                // PDF reads as a hyperlink even without the hover cursor.
                applyRunTextStyle(t, run, elementStyle);
                if (run.path("color").asText("").isEmpty()
                        && (elementStyle == null || elementStyle.isNull()
                        || elementStyle.path("color").asText("").isEmpty())) {
                    t.setFontColor(new DeviceRgb(0x25, 0x63, 0xEB));
                }
                if (!run.path("underline").asBoolean(false)) {
                    t.setUnderline(0.75f, -2f);
                }
            } else {
                t = new Text(textValue);
                applyRunTextStyle(t, run, elementStyle);
            }
            p.add(t);
            if (runIndexOut != null) {
                // Identity-keyed: iText shards long Text objects into multiple
                // TextRenderer children on wrap, but every shard shares the
                // same model-element reference. Looking up by identity resolves
                // every shard to the authored run ordinal.
                runIndexOut.put(t, runOrdinal);
            }
        }
        applyParagraphAlignmentOnly(p, elementStyle);
        return p;
    }

    /** Protocols accepted for PDF link annotations. Mirrors the client-side safe-list in `richContent.ts`. */
    private static final Set<String> SAFE_PDF_LINK_SCHEMES = Set.of("http", "https", "mailto", "tel");

    /**
     * Trim / validate a resolved link URL before turning it into a PdfAction.
     * Returns null if the URL is empty, malformed, or uses an unsafe scheme.
     * Variable-only hrefs that never got resolved (still look like `{{var}}`)
     * are rejected here — a real href at this point should always be a
     * concrete URI.
     */
    private static String sanitizePdfLinkHref(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > 2048) return null;
        if (trimmed.contains("{{") || trimmed.contains("}}")) return null;
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            if (!SAFE_PDF_LINK_SCHEMES.contains(scheme.toLowerCase())) return null;
            return uri.toString();
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private void applyRunTextStyle(Text t, JsonNode run, JsonNode elementStyle) {
        JsonNode base = (elementStyle == null || elementStyle.isNull())
                ? JsonNodeFactory.instance.objectNode()
                : elementStyle;
        float baseSize = (float) base.path("fontSize").asDouble(12);
        float size = run.has("fontSize") && !run.path("fontSize").isNull()
                ? (float) run.path("fontSize").asDouble(baseSize)
                : baseSize;
        t.setFontSize(size);
        boolean bold = run.has("bold")
                ? run.path("bold").asBoolean()
                : base.path("bold").asBoolean(false);
        boolean italic = run.has("italic")
                ? run.path("italic").asBoolean()
                : base.path("italic").asBoolean(false);
        if (parityOn()) {
            PdfFont font = resolveParityFont(base, bold, italic);
            if (font != null) {
                t.setFont(font);
            } else {
                if (bold) t.setBold();
                if (italic) t.setItalic();
            }
        } else {
            if (bold) t.setBold();
            if (italic) t.setItalic();
        }
        if (run.path("underline").asBoolean(false)) {
            t.setUnderline(0.75f, -2f);
        }
        if (run.path("strikethrough").asBoolean(false)) {
            t.setUnderline(0.75f, 3.2f);
        }
        boolean superscript = run.path("superscript").asBoolean(false);
        boolean subscript = run.path("subscript").asBoolean(false);
        if (superscript && !subscript) {
            float scriptSize = size * 0.72f;
            t.setFontSize(scriptSize);
            t.setTextRise(size * 0.33f);
        } else         if (subscript && !superscript) {
            float scriptSize = size * 0.72f;
            t.setFontSize(scriptSize);
            t.setTextRise(-size * 0.2f);
        }
        String runColor = run.path("color").asText("").trim();
        if (runColor.isEmpty()) {
            runColor = base.path("color").asText("").trim();
        }
        Color fontColor = parseCssColorToItext(runColor);
        if (fontColor != null) {
            t.setFontColor(fontColor);
        }
        String hl = run.path("highlightColor").asText("").trim();
        Color hlColor = parseCssColorToItext(hl);
        if (hlColor != null) {
            t.setBackgroundColor(hlColor);
        }
    }

    private void applyParagraphAlignmentOnly(Paragraph p, JsonNode style) {
        if (style == null || style.isNull()) {
            return;
        }
        String align = style.path("align").asText("left").toLowerCase();
        p.setTextAlignment(switch (align) {
            case "center" -> TextAlignment.CENTER;
            case "right" -> TextAlignment.RIGHT;
            default -> TextAlignment.LEFT;
        });
    }

    private void addTable(Document document, JsonNode el, JsonNode data, float pageHeight, int pageNumber) {
        JsonNode columns = el.path("columns");
        if (!columns.isArray() || columns.isEmpty()) {
            return;
        }
        // Loop mode vs static mode:
        //   - Loop: element.dataKey is an explicit string key/path; we look
        //     up an array at that key in the data context and emit one row
        //     per entry. Empty array → no body rows.
        //   - Static: no dataKey set → render `tablePreviewBodyRows` empty
        //     body rows, exactly matching what the canvas shows the author
        //     so the printed PDF isn't a header-only sliver. Previously
        //     this code defaulted the dataKey to "items" — if the caller
        //     didn't pass an "items" array, we silently fell through to
        //     an empty rows list and only the header row rendered
        //     (the "squished table" symptom).
        JsonNode dataKeyNode = el.get("dataKey");
        String dataKey = (dataKeyNode != null && dataKeyNode.isTextual())
                ? dataKeyNode.asText("").trim()
                : "";
        boolean loopMode = !dataKey.isEmpty();
        JsonNode rows;
        if (loopMode) {
            JsonNode resolved = resolveDataPath(data, dataKey);
            if (resolved == null || !resolved.isArray()) {
                resolved = data.path(dataKey);
            }
            rows = resolved.isArray() ? resolved : JsonNodeFactory.instance.arrayNode();
        } else {
            // Static mode — build row objects from `tableStaticCells`, the
            // frontend's per-cell storage for loop-off tables. Keys are
            // `"row,col"` (same coordinate convention as the backgrounds
            // maps); values are serialized rich-run JSON that
            // buildParagraphFromContent() already knows how to parse.
            //
            // When no cell is set at (row,col) we leave the field absent so
            // the body cell renders empty — identical to the prior behaviour
            // that synthesised empty rows.
            int previewRows = 3;
            JsonNode prNode = el.get("tablePreviewBodyRows");
            if (prNode != null && prNode.isNumber()) {
                int parsed = prNode.asInt(3);
                if (parsed > 0) previewRows = Math.min(30, parsed);
            }
            JsonNode staticCells = el.path("tableStaticCells");
            com.fasterxml.jackson.databind.node.ArrayNode synth = JsonNodeFactory.instance.arrayNode();
            for (int i = 0; i < previewRows; i++) {
                ObjectNode rowObj = JsonNodeFactory.instance.objectNode();
                if (staticCells != null && staticCells.isObject()) {
                    int colIdx = 0;
                    for (JsonNode col : columns) {
                        String colKey = col.path("key").asText("");
                        if (!colKey.isEmpty()) {
                            JsonNode cellVal = staticCells.get(i + "," + colIdx);
                            if (cellVal != null && cellVal.isTextual()) {
                                rowObj.set(colKey, cellVal);
                            }
                        }
                        colIdx++;
                    }
                }
                synth.add(rowObj);
            }
            rows = synth;
        }

        float[] colWidths = new float[columns.size()];
        JsonNode cwNode = el.path("columnWidths");
        float sumW = 0f;
        for (int i = 0; i < columns.size(); i++) {
            float w = (cwNode.isArray() && i < cwNode.size())
                    ? (float) cwNode.get(i).asDouble(1)
                    : 1f;
            if (w <= 0f) {
                w = 1f;
            }
            colWidths[i] = w;
            sumW += w;
        }
        if (sumW <= 0f) {
            sumW = columns.size();
            for (int i = 0; i < columns.size(); i++) {
                colWidths[i] = 1f;
            }
        }
        for (int i = 0; i < columns.size(); i++) {
            colWidths[i] = colWidths[i] / sumW * 100f;
        }
        Table table = new Table(UnitValue.createPercentArray(colWidths));

        // ── Row-height distribution ───────────────────────────────────────
        // iText sizes cells to their content by default — empty cells become
        // just padding-tall (~10pt), so a table designed to be 150pt tall on
        // canvas prints as a squished 40pt sliver. Distribute the element's
        // configured height across header + body rows so the printed table
        // matches the canvas footprint.
        //
        // `tableRowWeights` (per-body-row weights) lets the author give one
        // row more vertical space than another on the canvas; we mirror the
        // same distribution in the PDF. Header row weight is 1 by convention.
        float totalTableHeight = (float) el.path("height").asDouble(0);
        int bodyRowCountForHeight = 0;
        for (JsonNode row : rows) {
            if (row != null && row.isObject() && behaviourResolver.tableRowHidden(behaviour(el), row, data)) continue;
            bodyRowCountForHeight++;
        }
        float[] rowWeights = readTableRowWeights(el.get("tableRowWeights"), bodyRowCountForHeight);
        // Weighted distribution: header = 1, body rows = rowWeights[i]
        float weightSum = 1f; // header
        for (float wRow : rowWeights) weightSum += wRow;
        float headerRowHeight;
        float[] bodyRowHeights = new float[bodyRowCountForHeight];
        if (totalTableHeight > 0f && weightSum > 0f) {
            headerRowHeight = totalTableHeight / weightSum;
            for (int i = 0; i < bodyRowCountForHeight; i++) {
                bodyRowHeights[i] = (rowWeights[i] / weightSum) * totalTableHeight;
            }
        } else {
            // Fall back: let iText size cells normally.
            headerRowHeight = 0f;
            for (int i = 0; i < bodyRowCountForHeight; i++) bodyRowHeights[i] = 0f;
        }

        // Header paragraphs inherit the element's style (font, size, leading,
        // color) and force `bold`. In parity mode we also keep the font-family
        // consistent with body cells by starting from the element style and
        // overriding bold rather than constructing a bare {bold:true} node
        // that loses every other attribute.
        ObjectNode headerStyle;
        JsonNode elementStyle = el.path("style");
        if (elementStyle == null || elementStyle.isMissingNode() || elementStyle.isNull()) {
            headerStyle = objectMapper.createObjectNode();
        } else {
            headerStyle = objectMapper.createObjectNode();
            headerStyle.setAll((ObjectNode) elementStyle);
        }
        headerStyle.put("bold", true);

        int headerColIndex = 0;
        for (JsonNode col : columns) {
            Paragraph headerParagraph = buildParagraphFromContent(col.get("header"), data, null, headerStyle);
            Cell headerCell = applyCellPadding(new Cell()
                    .add(headerParagraph)
                    .setVerticalAlignment(VerticalAlignment.MIDDLE));
            applyCellBorderForParity(headerCell);
            if (headerRowHeight > 0f) {
                headerCell.setHeight(UnitValue.createPointValue(headerRowHeight));
            }
            Color headerBg = parseCssColorToItext(effectiveTableCellBackground(el, -1, headerColIndex));
            if (headerBg != null) {
                headerCell.setBackgroundColor(headerBg);
            }
            table.addHeaderCell(headerCell);
            headerColIndex++;
        }

        JsonNode behaviour = el.path("behaviour");
        int dataRowIndex = 0;
        for (JsonNode row : rows) {
            if (row != null && row.isObject() && behaviourResolver.tableRowHidden(behaviour, row, data)) {
                continue;
            }
            int bodyColIndex = 0;
            for (JsonNode col : columns) {
                String key = col.path("key").asText("");
                // Route through buildParagraphFromContent so rich runs (bold /
                // italic / color / link / inline variable) survive end-to-end.
                // Legacy `new Paragraph(cellValue(row, key, data))` stripped
                // every per-run attribute — the canvas showed a bold word in a
                // cell, the PDF rendered it plain. buildParagraphFromContent
                // handles both plain strings (legacy data) and rich-content
                // nodes (editor output), and applies the element's style so
                // fontSize / fontFamily / leading / alignment / color all come
                // from the same place as TEXT element rendering.
                JsonNode cellContent = row != null && row.has(key) ? row.get(key) : null;
                Paragraph para = buildParagraphFromContent(cellContent, data, row, elementStyle);
                LayoutBehaviourResolver.CellStyleDelta delta =
                        behaviourResolver.tableCellStyle(behaviour, row, data, bodyColIndex);
                if (delta.textColor() != null && !delta.textColor().isBlank()) {
                    Color tc = parseCssColorToItext(delta.textColor());
                    if (tc != null) {
                        para.setFontColor(tc);
                    }
                }
                Cell bodyCell = applyCellPadding(new Cell()
                        .add(para)
                        .setVerticalAlignment(VerticalAlignment.MIDDLE));
                applyCellBorderForParity(bodyCell);
                // Pin the cell height so the visible body row matches the
                // canvas row. Without this, an empty cell (static-mode
                // preview) would collapse to line-height + padding and the
                // whole table prints as a thin sliver.
                if (dataRowIndex < bodyRowHeights.length && bodyRowHeights[dataRowIndex] > 0f) {
                    bodyCell.setHeight(UnitValue.createPointValue(bodyRowHeights[dataRowIndex]));
                }
                Color cellBg = parseCssColorToItext(effectiveTableCellBackground(el, dataRowIndex, bodyColIndex));
                if (delta.backgroundColor() != null && !delta.backgroundColor().isBlank()) {
                    Color ob = parseCssColorToItext(delta.backgroundColor());
                    if (ob != null) {
                        cellBg = ob;
                    }
                }
                if (cellBg != null) {
                    bodyCell.setBackgroundColor(cellBg);
                }
                table.addCell(bodyCell);
                bodyColIndex++;
            }
            dataRowIndex++;
        }

        // Margin-top/bottom are canvas-level affordances for the author;
        // the PDF positions the table via `setFixedPosition` so the
        // table's `y` is already exact. Zero the margins so the rendered
        // table doesn't drift away from its canvas anchor.
        table.setMarginTop(0);
        table.setMarginBottom(0);

        float x = (float) el.path("x").asDouble(0);
        float elY = (float) el.path("y").asDouble(0);
        float w = (float) el.path("width").asDouble(0);
        if (w <= 0f) {
            w = document.getPdfDocument().getDefaultPageSize().getWidth() - x;
        }
        float h = (float) el.path("height").asDouble(120);
        float bottom = pageHeight - elY - h;
        // Explicit table width matches the canvas element's width. Dropped
        // `.useAllAvailableWidth()` from the Table initialiser above —
        // combined with `setFixedPosition` it was redundant and sometimes
        // caused iText to over-stretch the table beyond the element's
        // designed footprint.
        table.setWidth(UnitValue.createPointValue(w));
        table.setFixedPosition(pageNumber, x, bottom, w);
        document.add(table);
    }

    /** Behaviour access pulled out so the row-height pre-pass doesn't need to re-fetch. */
    private static JsonNode behaviour(JsonNode el) {
        return el.path("behaviour");
    }

    /**
     * Normalise the per-body-row weight array.
     * • If the layout doesn't supply valid weights (or the length doesn't
     *   match the visible row count), fall back to equal weights.
     * • Zero or negative weights are replaced with 1 to avoid collapsing
     *   a row to 0 height.
     */
    private static float[] readTableRowWeights(JsonNode rowWeightsNode, int bodyRowCount) {
        float[] w = new float[bodyRowCount];
        if (rowWeightsNode != null && rowWeightsNode.isArray() && rowWeightsNode.size() == bodyRowCount) {
            for (int i = 0; i < bodyRowCount; i++) {
                double d = rowWeightsNode.get(i).asDouble(1);
                w[i] = d > 0 ? (float) d : 1f;
            }
            return w;
        }
        java.util.Arrays.fill(w, 1f);
        return w;
    }

    private String cellValue(JsonNode row, String key, JsonNode globalData) {
        if (row != null && row.has(key)) {
            JsonNode v = row.get(key);
            if (v.isTextual()) {
                return substitute(v.asText(), globalData, row);
            }
            return v.asText("");
        }
        return "";
    }

    private String textFromStringObjectMap(JsonNode map, String key) {
        if (map == null || !map.isObject()) {
            return null;
        }
        JsonNode n = map.get(key);
        return n != null && n.isTextual() ? n.asText() : null;
    }

    /** Fill precedence: cell > column > row > element.style.backgroundColor (matches frontend). */
    private String effectiveTableCellBackground(JsonNode el, int row, int col) {
        JsonNode cellMap = el.path("tableCellBackgrounds");
        String cellBg = textFromStringObjectMap(cellMap, row + "," + col);
        if (cellBg != null && !cellBg.isBlank()) {
            return cellBg;
        }
        JsonNode colMap = el.path("tableColumnBackgrounds");
        String colBg = textFromStringObjectMap(colMap, String.valueOf(col));
        if (colBg != null && !colBg.isBlank()) {
            return colBg;
        }
        JsonNode rowMap = el.path("tableRowBackgrounds");
        String rowBg = textFromStringObjectMap(rowMap, String.valueOf(row));
        if (rowBg != null && !rowBg.isBlank()) {
            return rowBg;
        }
        // Element-level "table fill" — last-resort fallback mirroring the
        // canvas precedence in tableCellEffectiveBackground() on the frontend.
        // Without this, a solid background set on the TABLE element itself
        // (the Properties-panel "Fill" picker) only showed on the editor
        // canvas and vanished in the generated PDF.
        JsonNode style = el.path("style");
        if (style != null && style.isObject()) {
            JsonNode bg = style.get("backgroundColor");
            if (bg != null && bg.isTextual()) {
                String v = bg.asText("").trim();
                if (!v.isBlank()) return v;
            }
        }
        return null;
    }

    private static final Pattern CSS_RGB =
            Pattern.compile(
                    "rgba?\\(\\s*([0-9]+)\\s*,\\s*([0-9]+)\\s*,\\s*([0-9]+)(?:\\s*,\\s*([0-9.]+))?\\s*\\)",
                    Pattern.CASE_INSENSITIVE);

    private Color parseCssColorToItext(String css) {
        if (css == null) {
            return null;
        }
        css = css.trim();
        if (css.isEmpty() || "transparent".equalsIgnoreCase(css)) {
            return null;
        }
        if (css.charAt(0) == '#') {
            String h = css.substring(1);
            try {
                if (h.length() == 3) {
                    int r = Integer.parseInt(h.substring(0, 1) + h.substring(0, 1), 16);
                    int g = Integer.parseInt(h.substring(1, 2) + h.substring(1, 2), 16);
                    int b = Integer.parseInt(h.substring(2, 3) + h.substring(2, 3), 16);
                    return new DeviceRgb(r, g, b);
                }
                if (h.length() == 6) {
                    return new DeviceRgb(
                            Integer.parseInt(h.substring(0, 2), 16),
                            Integer.parseInt(h.substring(2, 4), 16),
                            Integer.parseInt(h.substring(4, 6), 16));
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Matcher m = CSS_RGB.matcher(css);
        if (m.find()) {
            try {
                int r = Math.min(255, Math.max(0, Integer.parseInt(m.group(1))));
                int g = Math.min(255, Math.max(0, Integer.parseInt(m.group(2))));
                int b = Math.min(255, Math.max(0, Integer.parseInt(m.group(3))));
                return new DeviceRgb(r, g, b);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Builds iText {@link ImageData} from a layout {@code src}: {@code data:image/...;base64,...} is decoded
     * to bytes; otherwise {@code src} is treated as a URL or file path (iText default).
     */
    private ImageData imageDataFromSrc(String src) throws MalformedURLException {
        String trimmed = src.trim();
        if (trimmed.regionMatches(true, 0, "data:", 0, 5)) {
            int comma = trimmed.indexOf(',');
            if (comma <= 5) {
                log.warn("IMAGE src data URL missing payload after comma");
                return null;
            }
            String header = trimmed.substring(5, comma);
            if (!header.toLowerCase(Locale.ROOT).contains("base64")) {
                log.warn("IMAGE data URL is not base64-encoded; only base64 data URLs are supported for PDF");
                return null;
            }
            String b64 = trimmed.substring(comma + 1).replaceAll("\\s+", "");
            try {
                byte[] raw = Base64.getMimeDecoder().decode(b64);
                return ImageDataFactory.create(raw);
            } catch (IllegalArgumentException e) {
                log.warn("IMAGE base64 decode failed: {}", e.getMessage());
                return null;
            }
        }
        return ImageDataFactory.create(trimmed);
    }

    // ── LIST element ──

    /**
     * Bullet styles cycle with indent level (canvas parity):
     *   disc → circle → square → dash → disc …
     */
    private static final String[] BULLET_CYCLE = { "disc", "circle", "square", "dash" };

    /**
     * Ordered styles cycle with indent level (canvas parity):
     *   number → alpha → roman → number …
     */
    private static final String[] ORDERED_CYCLE = { "number", "alpha", "roman" };

    /** Flat result of walking a list's tree structure, preserving per-row indent. */
    private static final class ListRow {
        final String text;
        final int indent;
        ListRow(String text, int indent) {
            this.text = text;
            this.indent = indent;
        }
    }

    private void addList(PdfDocument pdfDoc, Document document, JsonNode el, JsonNode data, float pageHeight, int pageNumber) {
        float x = (float) el.path("x").asDouble(0);
        float elY = (float) el.path("y").asDouble(0);
        float w = (float) el.path("width").asDouble(300);
        float h = (float) el.path("height").asDouble(120);
        float yTop = pageHeight - elY;
        float bottom = yTop - h;

        JsonNode style = el.path("style");
        String listStyleStr = el.path("listStyle").asText("disc");
        float itemSpacing = (float) el.path("listItemSpacing").asDouble(4);
        float indent = (float) el.path("listIndent").asDouble(16);
        int startNumber = el.path("listStartNumber").asInt(1);
        float fontSize = (float) style.path("fontSize").asDouble(12);
        float lineHeight = (float) style.path("lineHeight").asDouble(1.4);
        Color textColor = parseCssColorToItext(style.path("color").asText(""));
        if (textColor == null) textColor = ColorConstants.BLACK;

        // Background fill
        Color frameBg = parseCssColorToItext(style.path("backgroundColor").asText(""));
        if (frameBg != null) {
            PdfCanvas bgCanvas = new PdfCanvas(pdfDoc.getPage(pageNumber));
            bgCanvas.saveState();
            bgCanvas.setFillColor(frameBg);
            bgCanvas.rectangle(x, bottom, w, h);
            bgCanvas.fill();
            bgCanvas.restoreState();
        }

        // Resolve items (flat list of text + indent pairs).
        List<ListRow> rows = resolveListRows(el, data);
        if (rows.isEmpty()) return;

        // Per-indent ordered counters: ordered markers (number/alpha/roman)
        // reset their sequence on each new indent level so nested sublists
        // re-start at 1.
        java.util.Map<Integer, Integer> groupCountByIndent = new java.util.HashMap<>();

        // ── Render strategy ────────────────────────────────────────────────
        // We use iText's `Canvas` scoped to the element's bounding box. That
        // makes content flow from (x, yTop) DOWN, exactly like the editor.
        //
        // Each list row is its own single-row Table with 2 cells:
        //   [marker cell | text cell]
        //
        // Why a Table per row, not a shared container?
        //   • The text cell can WRAP into multiple lines. Table guarantees the
        //     marker cell's height matches the text cell's height, so the
        //     marker always stays on the same vertical run as its first line
        //     — something a naive "paragraph + marginLeft" cannot do, and
        //     something manual row-height math would get wrong the moment
        //     text wrapped (which is why earlier attempts drifted on long
        //     strings).
        //
        // For shape markers (disc/circle/square/dash) we attach a custom
        // CellRenderer that draws the vector shape AFTER iText has laid the
        // cell out — at that point `getOccupiedAreaBBox()` tells us exactly
        // where the cell's first line sits, so the bullet aligns perfectly.
        // Ordered markers (1./a./ii.) are plain text in the marker cell.
        // Pixel-parity LIST clip. iText's Canvas silently drops items that
        // don't fit vertically in its Rectangle — for CSS-flow-style parity
        // we need the renderer to ATTEMPT to draw every item, then let a
        // graphics-state clip at the element bbox hide any overflow. Without
        // this, canvas + PDF disagree on which items are visible when the
        // list sum-height exceeds the authored box (canvas shows more
        // because CSS flow is slightly tighter than iText's line metrics).
        //
        // Legacy path keeps the tight rectangle so pre-parity output is
        // bit-identical.
        PdfCanvas pdfCanvas = new PdfCanvas(pdfDoc.getPage(pageNumber));
        boolean listClipPushed = false;
        Rectangle canvasRect;
        if (parityOn()) {
            pdfCanvas.saveState();
            pdfCanvas.rectangle(x, bottom, w, h);
            pdfCanvas.clip();
            pdfCanvas.endPath();
            listClipPushed = true;
            // Extend the draw rectangle DOWNWARD so iText has room to lay out
            // every item. The top stays at the element's top edge (where
            // iText begins its flow); extra headroom below absorbs any items
            // that don't fit visually — those pixels are then masked by the
            // clip path above.
            float extraHeadroom = Math.max(pageHeight, 1000f);
            canvasRect = new Rectangle(x, bottom - extraHeadroom, w, h + extraHeadroom);
        } else {
            canvasRect = new Rectangle(x, bottom, w, h);
        }
        Canvas listCanvas = new Canvas(pdfCanvas, canvasRect);

        // Width reserved for the marker column INSIDE each row (after the
        // row's indent left-margin). Shape markers are drawn inside this
        // column via CellRenderer; ordered markers are text in this cell.
        float markerColWidth = Math.max(12f, indent);

        for (int i = 0; i < rows.size(); i++) {
            ListRow row = rows.get(i);
            int level = row.indent;
            // Reset any deeper indent counters when we step back up — matches
            // the canvas's group-at-depth bookkeeping so "1, 2, a, b, 3" works.
            groupCountByIndent.keySet().removeIf(k -> k > level);
            int groupIndex = groupCountByIndent.getOrDefault(level, 0);
            groupCountByIndent.put(level, groupIndex + 1);

            String textMarker = markerGlyphFor(listStyleStr, level, groupIndex, startNumber);
            String itemText = substitute(row.text, data, null);

            float rowLeftIndent = level * indent;
            // The row's own width inside the list is (w − rowLeftIndent).
            // Column 1 = markerColWidth (fixed pt). Column 2 = remainder.
            float textColWidth = Math.max(1f, w - rowLeftIndent - markerColWidth);

            Table rowTable = new Table(UnitValue.createPointArray(new float[]{ markerColWidth, textColWidth }));
            rowTable.setBorder(com.itextpdf.layout.borders.Border.NO_BORDER);
            // Canvas CSS `line-height` includes descender — row N ends exactly
            // where row N+1's leading starts. iText renders each line then
            // leaves a trailing descender gap (~fontSize × 0.25 for most Latin
            // fonts) BELOW the line box, so two iText rows stacked are further
            // apart than two canvas rows. Compensate by shortening the
            // inter-row margin under parity so row-to-row distance matches
            // canvas's `lineHeight + marginTop`.
            float descenderBuffer = parityOn() ? fontSize * 0.25f : 0f;
            float effectiveMarginTop = i == 0
                    ? 0f
                    : Math.max(0f, itemSpacing - descenderBuffer);
            rowTable.setMarginTop(effectiveMarginTop);
            rowTable.setMarginBottom(0);
            rowTable.setMarginLeft(rowLeftIndent);
            rowTable.setWidth(UnitValue.createPointValue(rowLeftIndent + markerColWidth + textColWidth));

            // ── Marker cell ────────────────────────────────────────────
            // NOTE: do NOT `setHeight(fontSize × lineHeight)` here — iText's
            // BlockRenderer drops ALL cell content when the laid-out line-box
            // exceeds the set height by even 1pt (same cliff that broke the
            // TEXT Div+setHeight approach earlier). The graphics-state clip
            // at the list bbox handles overflow containment; row-to-row
            // spacing is governed by the rowTable margin below.
            Cell markerCell = new Cell();
            markerCell.setBorder(com.itextpdf.layout.borders.Border.NO_BORDER);
            markerCell.setPadding(0);
            markerCell.setPaddingRight(2);
            markerCell.setVerticalAlignment(VerticalAlignment.TOP);

            if (!textMarker.isEmpty()) {
                // Single Paragraph path for BOTH bullet styles (Unicode glyphs
                // •, ○, ■, –) and ordered styles ("1.", "a.", "i."). Using a
                // text glyph instead of a vector-drawn shape lets iText's line
                // layout handle baseline alignment — the canvas does the same
                // thing, so the glyph sits at the x-height mid-line in both
                // renderers for free. Right-aligned so "1.", "10.", "100." all
                // terminate at the same column.
                Paragraph mp = new Paragraph(textMarker);
                applyTextStyle(mp, style);
                mp.setTextAlignment(TextAlignment.RIGHT);
                mp.setMargin(0);
                markerCell.add(mp);
            }

            rowTable.addCell(markerCell);

            // ── Text cell ──────────────────────────────────────────────
            Paragraph tp = new Paragraph(itemText);
            applyTextStyle(tp, style);
            tp.setMargin(0);
            Cell textCell = new Cell();
            textCell.setBorder(com.itextpdf.layout.borders.Border.NO_BORDER);
            textCell.setPadding(0);
            textCell.setPaddingLeft(2);
            textCell.setVerticalAlignment(VerticalAlignment.TOP);
            textCell.add(tp);
            rowTable.addCell(textCell);

            listCanvas.add(rowTable);
        }

        listCanvas.close();
        if (listClipPushed) {
            pdfCanvas.restoreState();
        }
    }

    /**
     * Custom cell renderer that draws a vector bullet shape inside the cell
     * after iText has decided the cell's final occupied area. This is how
     * the marker tracks its first-line row even when the adjacent text cell
     * wraps to multiple lines — the renderer reads the laid-out bbox, not a
     * pre-computed row height guess.
     */
    private static final class ShapeMarkerCellRenderer extends CellRenderer {
        private final String style;
        private final float fontSize;
        private final float lineHeight;
        private final Color color;

        ShapeMarkerCellRenderer(Cell modelElement, String style, float fontSize, float lineHeight, Color color) {
            super(modelElement);
            this.style = style;
            this.fontSize = fontSize;
            this.lineHeight = lineHeight;
            this.color = color;
        }

        @Override
        public com.itextpdf.layout.renderer.IRenderer getNextRenderer() {
            return new ShapeMarkerCellRenderer((Cell) getModelElement(), style, fontSize, lineHeight, color);
        }

        @Override
        public void draw(DrawContext drawContext) {
            super.draw(drawContext);
            Rectangle bbox = getOccupiedAreaBBox();
            PdfCanvas canvas = drawContext.getCanvas();
            // Align the bullet centre with the FIRST line's x-height middle
            // (where a Unicode `•` naturally centres in CSS flow). Math:
            //   baseline_y ≈ top − ascender − halfLeading
            //              ≈ top − 0.93 × fontSize − (lh − 1.17) × fontSize / 2
            //   x-height mid ≈ baseline + xHeight / 2
            //                ≈ baseline + 0.25 × fontSize
            //
            // For Latin fonts at lineHeight 1.4 this simplifies to roughly
            // `top − 0.78 × fontSize`. Using `fontSize × 0.78` instead of the
            // old `rowH × 0.5` shifts the bullet ~1pt lower — off the caps'
            // midline and onto the x-height midline, where the user expects
            // it to sit beside lowercase characters.
            float cy = bbox.getTop() - fontSize * 0.78f;
            float cx = bbox.getLeft() + bbox.getWidth() * 0.5f;
            PdfRendererService.drawShapeMarkerStatic(canvas, style, cx, cy, fontSize, color);
        }
    }

    /** Static helper so {@link ShapeMarkerCellRenderer} can draw without a service instance. */
    static void drawShapeMarkerStatic(PdfCanvas canvas, String style, float cx, float cy, float fontSize, Color color) {
        float r = fontSize * 0.18f;
        canvas.saveState();
        canvas.setFillColor(color);
        canvas.setStrokeColor(color);
        canvas.setLineWidth(Math.max(0.5f, fontSize * 0.08f));
        switch (style) {
            case "disc" -> {
                canvas.circle(cx, cy, r);
                canvas.fill();
            }
            case "circle" -> {
                canvas.circle(cx, cy, r);
                canvas.stroke();
            }
            case "square" -> {
                canvas.rectangle(cx - r, cy - r, r * 2, r * 2);
                canvas.fill();
            }
            case "dash" -> {
                float len = r * 1.8f;
                canvas.moveTo(cx - len, cy);
                canvas.lineTo(cx + len, cy);
                canvas.stroke();
            }
            default -> {
                canvas.circle(cx, cy, r);
                canvas.fill();
            }
        }
        canvas.restoreState();
    }

    /**
     * Unified marker resolver used by the list renderer. Returns the glyph (or
     * an empty string for {@code none}) that should sit in the marker cell for
     * a given row — a Unicode bullet character for unordered styles, or the
     * formatted number text (e.g. {@code "1."}, {@code "a."}, {@code "i."}) for
     * ordered styles. The codepoints match the canvas ({@code ListElementCanvas}):
     * disc → U+2022, circle → U+25CB, square → U+25A0, dash → U+2013. Using text
     * glyphs instead of vector shapes means iText positions the bullet at the
     * first line's x-height mid-line automatically, matching CSS.
     */
    private String markerGlyphFor(String baseStyle, int indentLevel, int groupIndex, int startNumber) {
        if ("none".equals(baseStyle)) return "";
        boolean isOrdered = "number".equals(baseStyle)
                || "alpha".equals(baseStyle)
                || "roman".equals(baseStyle);
        if (isOrdered) return textMarkerFor(baseStyle, indentLevel, groupIndex, startNumber);
        int baseIdx = Math.max(0, indexOf(BULLET_CYCLE, baseStyle));
        String effective = BULLET_CYCLE[(baseIdx + indentLevel) % BULLET_CYCLE.length];
        return switch (effective) {
            case "disc" -> "\u2022";
            case "circle" -> "\u25CB";
            case "square" -> "\u25A0";
            case "dash" -> "\u2013";
            default -> "\u2022";
        };
    }

    /**
     * Returns the bullet style to draw ({@code disc}/{@code circle}/{@code square}/{@code dash})
     * for a given row, or {@code null} if the base style is ordered or {@code none} — in
     * which case {@link #textMarkerFor} handles the marker instead.
     */
    private String effectiveBulletStyleFor(String baseStyle, int indentLevel) {
        if ("none".equals(baseStyle)) return null;
        if ("number".equals(baseStyle) || "alpha".equals(baseStyle) || "roman".equals(baseStyle)) {
            return null;
        }
        int baseIdx = Math.max(0, indexOf(BULLET_CYCLE, baseStyle));
        return BULLET_CYCLE[(baseIdx + indentLevel) % BULLET_CYCLE.length];
    }

    /**
     * Returns the ordered marker text ({@code "1."}, {@code "a."}, etc.) for a
     * row, or an empty string when the base style is a bullet style — shape
     * bullets are handled by {@link ShapeMarkerCellRenderer} drawing directly
     * onto the page canvas.
     */
    private String textMarkerFor(String baseStyle, int indentLevel, int groupIndex, int startNumber) {
        if ("none".equals(baseStyle)) return "";
        boolean isOrdered = "number".equals(baseStyle)
                || "alpha".equals(baseStyle)
                || "roman".equals(baseStyle);
        if (!isOrdered) return "";
        int baseIdx = indexOf(ORDERED_CYCLE, baseStyle);
        if (baseIdx < 0) baseIdx = 0;
        String effective = ORDERED_CYCLE[(baseIdx + indentLevel) % ORDERED_CYCLE.length];
        int n = startNumber + groupIndex;
        return switch (effective) {
            case "number" -> n + ".";
            case "alpha" -> listToAlpha(n) + ".";
            case "roman" -> listToRoman(n) + ".";
            default -> "";
        };
    }

    /**
     * Flatten a list element into a sequence of text + indent rows. Supports:
     *   • Loop mode (dataKey): reads the array from {@code data}, resolves each
     *     item through the template in {@code content}, recurses into a
     *     {@code children} field (or whatever {@code listChildrenKey} is set to).
     *   • Static mode (listItems): walks the {@code ListItemNode} tree that
     *     the frontend persists — objects with {@code text} + optional
     *     {@code children[]}. Previously this path called {@code item.asText("")}
     *     on the object node, which Jackson resolves to the empty string — so
     *     markers rendered but the typed text was silently dropped.
     */
    private List<ListRow> resolveListRows(JsonNode el, JsonNode data) {
        List<ListRow> out = new ArrayList<>();
        String dataKey = el.path("dataKey").asText("");
        if (!dataKey.isEmpty()) {
            JsonNode items = resolveDataPath(data, dataKey);
            if (items == null || !items.isArray()) items = data.path(dataKey);
            if (!items.isArray()) return out;
            String template = el.path("content").asText("{{.}}");
            String childrenKey = el.path("listChildrenKey").asText("").trim();
            if (childrenKey.isEmpty()) childrenKey = "children";
            walkLoopItems(items, template, childrenKey, 0, out);
            return out;
        }
        JsonNode listItems = el.path("listItems");
        if (!listItems.isArray()) return out;
        walkStaticItems(listItems, 0, out);
        return out;
    }

    private void walkStaticItems(JsonNode nodes, int depth, List<ListRow> out) {
        if (!nodes.isArray()) return;
        for (JsonNode node : nodes) {
            if (node.isTextual()) {
                // Legacy shape: plain string. Keep as-is.
                out.add(new ListRow(node.asText(""), depth));
            } else if (node.isObject()) {
                // Current shape: ListItemNode { text, children? }
                out.add(new ListRow(node.path("text").asText(""), depth));
                JsonNode children = node.path("children");
                if (children.isArray() && children.size() > 0) {
                    walkStaticItems(children, depth + 1, out);
                }
            } else {
                out.add(new ListRow(node.asText(""), depth));
            }
        }
    }

    private void walkLoopItems(JsonNode items, String template, String childrenKey, int depth, List<ListRow> out) {
        for (JsonNode item : items) {
            String text;
            if (item.isTextual()) {
                text = template.replace("{{.}}", item.asText(""));
            } else if (item.isObject()) {
                String resolved = template;
                var fields = item.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    resolved = resolved.replace("{{" + field.getKey() + "}}", field.getValue().asText(""));
                }
                text = resolved;
            } else {
                text = item.asText("");
            }
            out.add(new ListRow(text, depth));
            if (item.isObject()) {
                JsonNode children = item.path(childrenKey);
                if (children.isArray() && children.size() > 0) {
                    walkLoopItems(children, template, childrenKey, depth + 1, out);
                }
            }
        }
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) return i;
        }
        return -1;
    }

    private String listToAlpha(int n) {
        StringBuilder sb = new StringBuilder();
        int num = n;
        while (num > 0) {
            num--;
            sb.insert(0, (char) ('a' + (num % 26)));
            num /= 26;
        }
        return sb.toString();
    }

    private String listToRoman(int n) {
        if (n <= 0 || n > 3999) return String.valueOf(n);
        int[] vals = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] syms = {"m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vals.length; i++) {
            while (n >= vals[i]) {
                sb.append(syms[i]);
                n -= vals[i];
            }
        }
        return sb.toString();
    }

    private static boolean isAllowedImageUrl(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        String t = url.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("https://") || t.startsWith("http://") || t.startsWith("data:image/");
    }

    private void addImage(PdfDocument pdfDoc, Document document, JsonNode el, float pageHeight, int pageNumber) {
        String url = el.path("src").asText("");
        float x = (float) el.path("x").asDouble(0);
        float elY = (float) el.path("y").asDouble(0);
        float w = (float) el.path("width").asDouble(100);
        float h = (float) el.path("height").asDouble(100);
        float yTop = pageHeight - elY;
        float bottom = yTop - h;
        JsonNode style = el.path("style");
        Color backdrop = parseCssColorToItext(style.path("backgroundColor").asText(""));
        if (backdrop != null) {
            Rectangle rect = new Rectangle(x, bottom, w, h);
            PdfCanvas bg = new PdfCanvas(pdfDoc.getPage(pageNumber));
            bg.saveState();
            bg.setFillColor(backdrop);
            bg.rectangle(rect);
            bg.fill();
            bg.restoreState();
        }
        if (url.isBlank()) {
            return;
        }
        if (!isAllowedImageUrl(url)) {
            Paragraph err = new Paragraph("[image: URL must be http(s) or data:image]");
            err.setFixedPosition(pageNumber, x, bottom, 260);
            document.add(err);
            return;
        }
        try {
            ImageData data = imageDataFromSrc(url);
            if (data == null) {
                Paragraph err = new Paragraph("[image: unsupported or invalid src]");
                err.setFixedPosition(pageNumber, x, bottom, 200);
                document.add(err);
                return;
            }
            Image img = new Image(data);
            img.scaleToFit(w, h);
            // Canvas renders images with CSS `object-contain` inside a
            // `flex items-center justify-center` box — the image sits in the
            // middle of the element, empty space symmetric on each axis when
            // the aspect ratio doesn't match. iText's `scaleToFit` preserves
            // the aspect ratio too, but {@code setFixedPosition} anchors the
            // bottom-left, so any mismatch stacks all the empty space at the
            // top + right. Compute the rendered dimensions ourselves and
            // offset the anchor so the image centers in the box.
            float renderedW = w;
            float renderedH = h;
            if (parityOn()) {
                float intrinsicW = data.getWidth();
                float intrinsicH = data.getHeight();
                if (intrinsicW > 0f && intrinsicH > 0f) {
                    float scale = Math.min(w / intrinsicW, h / intrinsicH);
                    renderedW = intrinsicW * scale;
                    renderedH = intrinsicH * scale;
                }
            }
            float anchorX = parityOn() ? x + (w - renderedW) / 2f : x;
            float anchorY = parityOn() ? bottom + (h - renderedH) / 2f : bottom;

            // Phase 4.7 — IMAGE rotation around the element center. iText's
            // Image.setRotationAngle rotates around the image's bottom-left by
            // default; we offset setFixedPosition to keep the rotated bbox's
            // center on the element box center. The rotation math uses the
            // RENDERED image dimensions (post-scaleToFit), not the authored
            // element w/h — those only matter for the centering offset above.
            if (parityOn()) {
                double rotationDeg = style.path("rotation").asDouble(0);
                if (rotationDeg != 0) {
                    double theta = Math.toRadians(-rotationDeg);
                    float cos = (float) Math.cos(theta);
                    float sin = (float) Math.sin(theta);
                    anchorX += renderedW / 2f * (1f - cos) + renderedH / 2f * sin;
                    anchorY += renderedH / 2f * (1f - cos) - renderedW / 2f * sin;
                    img.setRotationAngle(theta);
                }
            }
            applyOpacityToElement(img, style);
            img.setFixedPosition(pageNumber, anchorX, anchorY);
            document.add(img);
        } catch (MalformedURLException e) {
            log.warn("IMAGE MalformedURLException: {}", e.getMessage());
            Paragraph err = new Paragraph("[invalid image url]");
            err.setFixedPosition(pageNumber, x, bottom, 200);
            document.add(err);
        } catch (Exception e) {
            log.warn("IMAGE could not be embedded: {} — {}", e.getClass().getSimpleName(), e.getMessage());
            Paragraph err = new Paragraph("[image failed]");
            err.setFixedPosition(pageNumber, x, bottom, 200);
            document.add(err);
        }
    }

    private void addLine(PdfDocument pdfDoc, JsonNode el, float pageHeight, int pageNumber) {
        float x1 = (float) el.path("x").asDouble(0);
        float elY = (float) el.path("y").asDouble(0);
        float elH = (float) el.path("height").asDouble(4);
        float length = (float) el.path("width").asDouble(400);
        float stroke = (float) el.path("strokeWidth").asDouble(1);
        float yPdf = pageHeight - elY - elH / 2f;
        float x2 = x1 + length;

        JsonNode style = el.path("style");
        Color strokeColor = parseCssColorToItext(style.path("color").asText(""));
        if (strokeColor == null) {
            strokeColor = ColorConstants.BLACK;
        }

        PdfCanvas canvas = new PdfCanvas(pdfDoc.getPage(pageNumber));
        canvas.saveState();
        applyOpacityIfAny(canvas, style);
        applyRotationIfAny(canvas, style, x1 + length / 2f, yPdf);
        canvas.setStrokeColor(strokeColor);
        float effectiveStroke = Math.max(0.25f, stroke);
        canvas.setLineWidth(effectiveStroke);
        applyLineStyleIfAny(canvas, style, effectiveStroke);
        canvas.moveTo(x1, yPdf);
        canvas.lineTo(x2, yPdf);
        canvas.stroke();
        canvas.restoreState();
    }

    private void addBox(PdfDocument pdfDoc, JsonNode el, float pageHeight, int pageNumber) {
        float x = (float) el.path("x").asDouble(0);
        float elY = (float) el.path("y").asDouble(0);
        float w = (float) el.path("width").asDouble(100);
        float h = (float) el.path("height").asDouble(50);
        float bottom = pageHeight - elY - h;
        Rectangle rect = new Rectangle(x, bottom, w, h);
        JsonNode style = el.path("style");
        Color fill = parseCssColorToItext(style.path("backgroundColor").asText(""));
        Color stroke = parseCssColorToItext(style.path("color").asText(""));
        if (stroke == null) {
            stroke = ColorConstants.GRAY;
        }

        // Parity-aware: honor borderWidth + lineStyle. Legacy rendering used a
        // hardcoded 2pt dashed (3/2) stroke regardless of author intent — that
        // still kicks in when the author hasn't supplied explicit values so
        // legacy layouts don't shift.
        float borderWidth = parityOn()
                ? (float) style.path("borderWidth").asDouble(2)
                : 2f;
        String lineStyle = parityOn()
                ? style.path("lineStyle").asText("dashed")
                : "dashed";

        // Phase 6a — BOX borderRadius. `roundRectangle` requires radius
        // clamped to half the shorter side; over-large authored values are
        // silently clamped so iText doesn't throw. Off the parity flag we
        // keep square corners (legacy behaviour).
        float radius = 0f;
        if (parityOn()) {
            double authored = style.path("borderRadius").asDouble(0);
            if (authored > 0) {
                radius = (float) Math.min(authored, Math.min(w, h) / 2f);
            }
        }

        // Phase 6c — drop shadow rendered as a rasterised PNG overlay UNDER
        // the element. Runs before the main draw so the shadow lives
        // beneath; offset + blur + color read from style.shadow.
        JsonNode shadowNode = style.path("shadow");
        if (parityOn() && shadowNode.isObject()) {
            paintShadowUnder(pdfDoc, shadowNode,
                    radius > 0f ? ShadowRasterizer.Shape.ROUNDED_RECTANGLE : ShadowRasterizer.Shape.RECTANGLE,
                    radius, x, bottom, w, h, pageNumber);
        }

        // Phase 6d — gradient fill takes precedence over solid `fill` when
        // `bgGradient` is set and parses successfully. iText's
        // `setFillColorGradient` swaps the graphics-state fill for a gradient
        // pattern spanning the element's bbox.
        com.itextpdf.kernel.colors.gradients.LinearGradientBuilder bgGradient =
                parseLinearGradientToItext(style.path("bgGradient"), x, bottom, w, h);

        PdfCanvas canvas = new PdfCanvas(pdfDoc.getPage(pageNumber));
        canvas.saveState();
        applyOpacityIfAny(canvas, style);
        applyRotationIfAny(canvas, style, x + w / 2f, bottom + h / 2f);
        if (bgGradient != null) {
            // `buildColor(targetBBox, affineMatrix, document)` returns a
            // gradient-backed Color we can pass to setFillColor like any solid.
            Color gradientColor = bgGradient.buildColor(rect, null, pdfDoc);
            canvas.setFillColor(gradientColor);
            if (radius > 0f) canvas.roundRectangle(x, bottom, w, h, radius);
            else canvas.rectangle(rect);
            canvas.fill();
        } else if (fill != null) {
            canvas.setFillColor(fill);
            if (radius > 0f) canvas.roundRectangle(x, bottom, w, h, radius);
            else canvas.rectangle(rect);
            canvas.fill();
        }
        canvas.setStrokeColor(stroke);
        canvas.setLineWidth(borderWidth);
        applyLineDashForStyle(canvas, lineStyle, borderWidth);
        if (radius > 0f) canvas.roundRectangle(x, bottom, w, h, radius);
        else canvas.rectangle(rect);
        canvas.stroke();
        canvas.restoreState();
    }

    /** Layout Y measured from top of page → iText PDF Y (bottom-origin). */
    private float layoutYToPdf(float pageHeight, float yFromTop) {
        return pageHeight - yFromTop;
    }

    /**
     * Apply {@code style.rotation} (degrees clockwise) around the given
     * pivot point by concatenating a rotation matrix onto the current
     * canvas graphics state. Caller must have already pushed a saveState.
     * Off the parity flag or when rotation is 0/missing, this is a no-op —
     * legacy renders stay axis-aligned.
     */
    private void applyRotationIfAny(PdfCanvas canvas, JsonNode style, float pivotX, float pivotY) {
        if (!parityOn()) return;
        double rotationDeg = style.path("rotation").asDouble(0);
        if (rotationDeg == 0) return;
        // Canvas rotation is clockwise-positive; PDF matrix is counter-clockwise.
        double theta = Math.toRadians(-rotationDeg);
        float cos = (float) Math.cos(theta);
        float sin = (float) Math.sin(theta);
        // Translate → rotate → translate back, composed into a single matrix.
        canvas.concatMatrix(cos, sin, -sin, cos,
                pivotX - cos * pivotX + sin * pivotY,
                pivotY - sin * pivotX - cos * pivotY);
    }

    /**
     * Phase 6c — paint a rasterised shadow directly onto the page's
     * {@link PdfCanvas} at the right pt dimensions. Called from each element
     * renderer before its own draw so the shadow lives underneath.
     *
     * <p>The rasteriser returns a PNG padded by {@code blur×2} on each side
     * (for the edge fall-off); we position the bottom-left of the PNG at
     * {@code (x - pad, bottom - pad)} so the element's visual origin lands in
     * the right spot.
     */
    private void paintShadowUnder(PdfDocument pdfDoc, JsonNode shadowNode,
                                  ShadowRasterizer.Shape shape, float cornerRadiusPt,
                                  float x, float bottom, float w, float h, int pageNumber) {
        byte[] png = ShadowRasterizer.rasterize(w, h, shadowNode, shape, cornerRadiusPt);
        if (png == null) return;
        try {
            com.itextpdf.io.image.ImageData shadowData =
                    com.itextpdf.io.image.ImageDataFactory.create(png);
            float blur = (float) shadowNode.path("blur").asDouble(4);
            float padPt = blur * 2f;
            // Draw via PdfCanvas.addImageFittedIntoRectangle — avoids the
            // Document-level add() path which isn't available in pure
            // PdfCanvas helpers like addBox.
            PdfCanvas shadowCanvas = new PdfCanvas(pdfDoc.getPage(pageNumber));
            shadowCanvas.saveState();
            com.itextpdf.kernel.geom.Rectangle targetRect =
                    new com.itextpdf.kernel.geom.Rectangle(
                            x - padPt, bottom - padPt,
                            w + padPt * 2f, h + padPt * 2f);
            shadowCanvas.addImageFittedIntoRectangle(shadowData, targetRect, false);
            shadowCanvas.restoreState();
        } catch (Exception e) {
            log.warn("Shadow embed failed: {}", e.getMessage());
        }
    }

    /**
     * Phase 6d — parse a {@code style.bgGradient} node into an iText
     * {@link com.itextpdf.kernel.colors.gradients.AbstractLinearGradientBuilder}
     * that paints over the given bounding box. Returns null when the gradient
     * is missing, malformed, or parity is off (callers fall back to solid
     * fill via {@code backgroundColor}).
     *
     * <p>CSS linear-gradient semantics: angle 0 = bottom→top, 90 = left→right
     * (clockwise-positive). We build the PDF gradient vector in y-up PDF coords
     * so iText paints identically to the browser; the length is the standard
     * CSS "projects corners to 0 and 1" formula {@code |w·sin θ| + |h·cos θ|}.
     */
    private com.itextpdf.kernel.colors.gradients.LinearGradientBuilder
            parseLinearGradientToItext(JsonNode gradient, float x, float bottom, float w, float h) {
        if (!parityOn()) return null;
        if (gradient == null || gradient.isMissingNode() || gradient.isNull()) return null;
        String type = gradient.path("type").asText("linear");
        if (!"linear".equals(type)) {
            // Radial / text-gradient follow-on sub-phases; for now the
            // backgrounds path only handles linear. Callers fall back to
            // solid fill for other types.
            return null;
        }
        JsonNode stops = gradient.path("stops");
        if (!stops.isArray() || stops.size() < 2) return null;

        double angleDeg = gradient.path("angle").asDouble(180); // 180 = top→bottom default
        double theta = Math.toRadians(angleDeg);
        float dx = (float) Math.sin(theta);
        // CSS gradient angle 0 = "to top"; in PDF y-up, "to top" is +y. For
        // angle=0 we want dy=+1. cos(0)=1, so dy = cos(theta) matches.
        float dy = (float) Math.cos(theta);
        float cx = x + w / 2f;
        float cy = bottom + h / 2f;
        float len = Math.abs(w * dx) + Math.abs(h * dy);
        float x0 = cx - dx * len / 2f;
        float y0 = cy - dy * len / 2f;
        float x1 = cx + dx * len / 2f;
        float y1 = cy + dy * len / 2f;

        com.itextpdf.kernel.colors.gradients.LinearGradientBuilder builder =
                new com.itextpdf.kernel.colors.gradients.LinearGradientBuilder();
        builder.setGradientVector(x0, y0, x1, y1);
        builder.setSpreadMethod(com.itextpdf.kernel.colors.gradients.GradientSpreadMethod.PAD);
        for (JsonNode stop : stops) {
            String cssColor = stop.path("color").asText("");
            Color c = parseCssColorToItext(cssColor);
            if (c == null) c = ColorConstants.WHITE;
            double offset = stop.path("position").asDouble(0);
            float[] rgb = c.getColorValue();
            builder.addColorStop(new com.itextpdf.kernel.colors.gradients.GradientColorStop(
                    rgb, (float) offset,
                    com.itextpdf.kernel.colors.gradients.GradientColorStop.OffsetType.RELATIVE));
        }
        return builder;
    }

    /**
     * Phase 6b — opacity. Reads {@code style.opacity} (0..1; default 1) and,
     * when parity is on, pushes a {@link PdfExtGState} with matching fill +
     * stroke alpha onto the canvas graphics state. Must be called INSIDE a
     * {@code saveState / restoreState} scope so the opacity doesn't leak into
     * the next element's draw.
     */
    private void applyOpacityIfAny(PdfCanvas canvas, JsonNode style) {
        if (!parityOn()) return;
        double opacity = style.path("opacity").asDouble(1);
        if (opacity >= 1.0 || opacity < 0) return;
        float a = (float) opacity;
        PdfExtGState gs = new PdfExtGState().setFillOpacity(a).setStrokeOpacity(a);
        canvas.setExtGState(gs);
    }

    /**
     * Propagate opacity to a Paragraph/Image/Table element-level draw. iText
     * 7.2.5 exposes a single {@code Property.OPACITY} which applies to both
     * fill and stroke of the laid-out element.
     */
    private <T extends com.itextpdf.layout.IPropertyContainer> T applyOpacityToElement(T element, JsonNode style) {
        if (!parityOn()) return element;
        double opacity = style.path("opacity").asDouble(1);
        if (opacity >= 1.0 || opacity < 0) return element;
        element.setProperty(Property.OPACITY, (float) opacity);
        return element;
    }

    /** Read {@code style.lineStyle} (solid/dashed/dotted) and apply the matching dash pattern. */
    private void applyLineStyleIfAny(PdfCanvas canvas, JsonNode style, float strokeWidth) {
        if (!parityOn()) return;
        String lineStyle = style.path("lineStyle").asText("solid");
        applyLineDashForStyle(canvas, lineStyle, strokeWidth);
    }

    /**
     * Convenience wrapper that computes the element's bbox center (in PDF
     * coords) from {@code el.x/y/width/height} and delegates to
     * {@link #applyRotationIfAny}. Used by every shape renderer so one
     * {@code style.rotation} field rotates the whole primitive uniformly.
     */
    private void applyShapeRotationIfAny(PdfCanvas canvas, JsonNode el, float pageHeight) {
        if (!parityOn()) return;
        JsonNode style = el.path("style");
        double rotationDeg = style.path("rotation").asDouble(0);
        if (rotationDeg == 0) return;
        float x = (float) el.path("x").asDouble(0);
        float elY = (float) el.path("y").asDouble(0);
        float w = (float) el.path("width").asDouble(0);
        float h = (float) el.path("height").asDouble(0);
        if (w <= 0f || h <= 0f) return;
        float cx = x + w / 2f;
        float cy = pageHeight - elY - h / 2f;
        applyRotationIfAny(canvas, style, cx, cy);
    }

    /**
     * Apply the dash pattern for a given logical lineStyle. Values mirror
     * the frontend {@code lineStyle} field.
     * <ul>
     *   <li>{@code solid}  — no dash (clears any previously-set pattern)</li>
     *   <li>{@code dashed} — 3×stroke on / 2×stroke off</li>
     *   <li>{@code dotted} — 1×stroke on / 1×stroke off</li>
     * </ul>
     */
    private void applyLineDashForStyle(PdfCanvas canvas, String lineStyle, float strokeWidth) {
        float w = Math.max(0.25f, strokeWidth);
        switch (lineStyle) {
            case "dashed" -> canvas.setLineDash(w * 3f, w * 2f);
            case "dotted" -> canvas.setLineDash(w, w);
            default -> canvas.setLineDash(new float[]{}, 0);
        }
    }

    /** Closed elliptical path (polygon approximation), as one PDF subpath. */
    private void ellipseRingPath(
            PdfCanvas canvas, float pageHeight, float x, float elY, float w, float h, int seg) {
        float cx = x + w / 2f;
        float cyTop = elY + h / 2f;
        float rx = Math.max(0.25f, w / 2f);
        float ry = Math.max(0.25f, h / 2f);
        for (int i = 0; i <= seg; i++) {
            double t = i * 2 * Math.PI / seg;
            float px = cx + rx * (float) Math.cos(t);
            float pyTop = cyTop + ry * (float) Math.sin(t);
            float pyPdf = layoutYToPdf(pageHeight, pyTop);
            if (i == 0) {
                canvas.moveTo(px, pyPdf);
            } else {
                canvas.lineTo(px, pyPdf);
            }
        }
        canvas.closePath();
    }

    private void addEllipseShape(PdfDocument pdfDoc, JsonNode el, float pageHeight, int pageNumber) {
        float x = (float) el.path("x").asDouble(0);
        float elY = (float) el.path("y").asDouble(0);
        float w = (float) el.path("width").asDouble(100);
        float h = (float) el.path("height").asDouble(80);
        JsonNode style = el.path("style");
        Color fill = parseCssColorToItext(style.path("backgroundColor").asText(""));
        Color stroke = parseCssColorToItext(style.path("color").asText(""));
        if (stroke == null) {
            stroke = ColorConstants.BLACK;
        }
        float sw = (float) el.path("strokeWidth").asDouble(2);
        int seg = parityOn() ? 64 : 40;
        PdfCanvas canvas = new PdfCanvas(pdfDoc.getPage(pageNumber));
        canvas.saveState();
        applyShapeRotationIfAny(canvas, el, pageHeight);
        applyLineStyleIfAny(canvas, style, sw);
        ellipseRingPath(canvas, pageHeight, x, elY, w, h, seg);
        canvas.setStrokeColor(stroke);
        canvas.setLineWidth(Math.max(0.25f, sw));
        if (fill != null) {
            canvas.setFillColor(fill);
            canvas.fillStroke();
        } else {
            canvas.stroke();
        }
        canvas.restoreState();
    }

    /** Concentric ellipses: fill (even-odd) and/or stroke outer and inner outlines. */
    private void addRingShape(PdfDocument pdfDoc, JsonNode el, float pageHeight, int pageNumber) {
        float x = (float) el.path("x").asDouble(0);
        float elY = (float) el.path("y").asDouble(0);
        float w = (float) el.path("width").asDouble(120);
        float h = (float) el.path("height").asDouble(120);
        double ratioD = el.path("ringInnerRatio").asDouble(0.55);
        float ratio = (float) Math.max(0.05, Math.min(0.95, ratioD));
        float iw = w * ratio;
        float ih = h * ratio;
        float ox = x + (w - iw) / 2f;
        float oy = elY + (h - ih) / 2f;
        JsonNode style = el.path("style");
        Color fill = parseCssColorToItext(style.path("backgroundColor").asText(""));
        Color stroke = parseCssColorToItext(style.path("color").asText(""));
        if (stroke == null) {
            stroke = ColorConstants.BLACK;
        }
        float sw = (float) el.path("strokeWidth").asDouble(2);
        int seg = parityOn() ? 64 : 36;
        PdfCanvas canvas = new PdfCanvas(pdfDoc.getPage(pageNumber));
        canvas.saveState();
        applyShapeRotationIfAny(canvas, el, pageHeight);
        if (fill != null) {
            ellipseRingPath(canvas, pageHeight, x, elY, w, h, seg);
            ellipseRingPath(canvas, pageHeight, ox, oy, iw, ih, seg);
            canvas.setFillColor(fill);
            canvas.eoFill();
        }
        if (sw >= 0.25f) {
            applyLineStyleIfAny(canvas, style, sw);
            canvas.setStrokeColor(stroke);
            canvas.setLineWidth(Math.max(0.25f, sw));
            ellipseRingPath(canvas, pageHeight, x, elY, w, h, seg);
            canvas.stroke();
            ellipseRingPath(canvas, pageHeight, ox, oy, iw, ih, seg);
            canvas.stroke();
        }
        canvas.restoreState();
    }

    private void addTriangleShape(PdfDocument pdfDoc, JsonNode el, float pageHeight, int pageNumber) {
        float x = (float) el.path("x").asDouble(0);
        float elY = (float) el.path("y").asDouble(0);
        float w = (float) el.path("width").asDouble(100);
        float h = (float) el.path("height").asDouble(90);
        strokeFilledPolygon(
                pdfDoc,
                pageHeight,
                pageNumber,
                el,
                new float[] {x + w / 2f, x + w, x},
                new float[] {elY, elY + h, elY + h});
    }

    private void addDiamondShape(PdfDocument pdfDoc, JsonNode el, float pageHeight, int pageNumber) {
        float x = (float) el.path("x").asDouble(0);
        float elY = (float) el.path("y").asDouble(0);
        float w = (float) el.path("width").asDouble(100);
        float h = (float) el.path("height").asDouble(100);
        strokeFilledPolygon(
                pdfDoc,
                pageHeight,
                pageNumber,
                el,
                new float[] {x + w / 2f, x + w, x + w / 2f, x},
                new float[] {elY, elY + h / 2f, elY + h, elY + h / 2f});
    }

    private void addStarShape(PdfDocument pdfDoc, JsonNode el, float pageHeight, int pageNumber) {
        float x = (float) el.path("x").asDouble(0);
        float elY = (float) el.path("y").asDouble(0);
        float w = (float) el.path("width").asDouble(100);
        float h = (float) el.path("height").asDouble(100);
        float cx = x + w / 2f;
        float cyTop = elY + h / 2f;
        float ro = Math.min(w, h) / 2f;
        float ri = ro * 0.38f;
        float[] xs = new float[10];
        float[] ys = new float[10];
        for (int i = 0; i < 10; i++) {
            double a = (i * Math.PI) / 5.0 - Math.PI / 2.0;
            float r = (i % 2 == 0) ? ro : ri;
            xs[i] = cx + r * (float) Math.cos(a);
            ys[i] = cyTop + r * (float) Math.sin(a);
        }
        strokeFilledPolygon(pdfDoc, pageHeight, pageNumber, el, xs, ys);
    }

    private void addArrowShape(PdfDocument pdfDoc, JsonNode el, float pageHeight, int pageNumber) {
        float x = (float) el.path("x").asDouble(0);
        float elY = (float) el.path("y").asDouble(0);
        float w = (float) el.path("width").asDouble(140);
        float h = (float) el.path("height").asDouble(48);
        float t = Math.min(h * 0.35f, w * 0.18f);
        float mid = elY + h / 2f;
        float x0 = x;
        float xShaft = x + w * 0.68f;
        float xTip = x + w;
        strokeFilledPolygon(
                pdfDoc,
                pageHeight,
                pageNumber,
                el,
                new float[] {x0, xShaft, xShaft, xTip, xShaft, xShaft, x0, x0},
                new float[] {
                    mid - t / 2f,
                    mid - t / 2f,
                    elY,
                    mid,
                    elY + h,
                    mid + t / 2f,
                    mid + t / 2f,
                    mid - t / 2f
                });
    }

    private void strokeFilledPolygon(
            PdfDocument pdfDoc, float pageHeight, int pageNumber, JsonNode el, float[] xsTop, float[] ysTop) {
        if (xsTop.length < 3 || xsTop.length != ysTop.length) {
            return;
        }
        JsonNode style = el.path("style");
        Color fill = parseCssColorToItext(style.path("backgroundColor").asText(""));
        Color stroke = parseCssColorToItext(style.path("color").asText(""));
        if (stroke == null) {
            stroke = ColorConstants.BLACK;
        }
        float sw = (float) el.path("strokeWidth").asDouble(2);
        PdfCanvas canvas = new PdfCanvas(pdfDoc.getPage(pageNumber));
        canvas.saveState();
        applyShapeRotationIfAny(canvas, el, pageHeight);
        float py0 = layoutYToPdf(pageHeight, ysTop[0]);
        canvas.moveTo(xsTop[0], py0);
        for (int i = 1; i < xsTop.length; i++) {
            canvas.lineTo(xsTop[i], layoutYToPdf(pageHeight, ysTop[i]));
        }
        canvas.closePath();
        applyLineStyleIfAny(canvas, style, sw);
        canvas.setStrokeColor(stroke);
        canvas.setLineWidth(Math.max(0.25f, sw));
        if (fill != null) {
            canvas.setFillColor(fill);
            canvas.fillStroke();
        } else {
            canvas.stroke();
        }
        canvas.restoreState();
    }

    /** Append one closed ring (layout top-left coords relative to element origin) to the current path. */
    private void appendLayoutRingToPath(
            PdfCanvas canvas, float pageHeight, float x0, float elY, JsonNode ring) {
        if (!ring.isArray() || ring.size() < 2) {
            return;
        }
        boolean first = true;
        for (JsonNode pt : ring) {
            if (!pt.isArray() || pt.size() < 2) {
                continue;
            }
            float lx = (float) pt.get(0).asDouble();
            float ly = (float) pt.get(1).asDouble();
            float px = x0 + lx;
            float pyTop = elY + ly;
            float pyPdf = layoutYToPdf(pageHeight, pyTop);
            if (first) {
                canvas.moveTo(px, pyPdf);
                first = false;
            } else {
                canvas.lineTo(px, pyPdf);
            }
        }
        canvas.closePath();
    }

    /**
     * MERGED_SHAPE: polygon union or boolean difference (e.g. star ring). Each entry in {@code shapePolys} is one
     * region with optional holes; even-odd fill matches canvas/SVG.
     */
    private void addMergedShape(PdfDocument pdfDoc, JsonNode el, float pageHeight, int pageNumber) {
        float x0 = (float) el.path("x").asDouble(0);
        float elY = (float) el.path("y").asDouble(0);
        JsonNode polys = el.path("shapePolys");
        if (!polys.isArray() || polys.isEmpty()) {
            return;
        }
        JsonNode style = el.path("style");
        Color fill = parseCssColorToItext(style.path("backgroundColor").asText(""));
        Color stroke = parseCssColorToItext(style.path("color").asText(""));
        if (stroke == null) {
            stroke = ColorConstants.BLACK;
        }
        float sw = (float) el.path("strokeWidth").asDouble(2);
        PdfCanvas canvas = new PdfCanvas(pdfDoc.getPage(pageNumber));
        canvas.saveState();
        applyShapeRotationIfAny(canvas, el, pageHeight);
        if (fill != null) {
            canvas.setFillColor(fill);
            for (JsonNode poly : polys) {
                if (!poly.isArray()) {
                    continue;
                }
                for (JsonNode ring : poly) {
                    appendLayoutRingToPath(canvas, pageHeight, x0, elY, ring);
                }
                canvas.eoFill();
            }
        }
        if (sw >= 0.25f) {
            applyLineStyleIfAny(canvas, style, sw);
            canvas.setStrokeColor(stroke);
            canvas.setLineWidth(Math.max(0.25f, sw));
            for (JsonNode poly : polys) {
                if (!poly.isArray()) {
                    continue;
                }
                for (JsonNode ring : poly) {
                    appendLayoutRingToPath(canvas, pageHeight, x0, elY, ring);
                    canvas.stroke();
                }
            }
        }
        canvas.restoreState();
    }

    private void applyTextStyle(Paragraph p, JsonNode style) {
        if (style.isMissingNode() || style.isNull()) {
            if (parityOn()) {
                // Even without a style, under parity we want Inter + canvas default leading
                // so the element matches the canvas's `RichTextBlockPreview` defaults.
                PdfFont font = resolveParityFont(null, false, false);
                if (font != null) p.setFont(font);
                p.setMultipliedLeading(DEFAULT_LINE_HEIGHT);
            }
            return;
        }
        float fontSize = (float) style.path("fontSize").asDouble(12);
        p.setFontSize(fontSize);
        boolean bold = style.path("bold").asBoolean(false);
        boolean italic = style.path("italic").asBoolean(false);
        if (parityOn()) {
            // Real bold/italic TTFs via the registry — never fall back to iText's
            // synthetic `setBold()`/`setItalic()` (double-stroke / 15° skew)
            // because those produce glyph widths that disagree with the canvas.
            PdfFont font = resolveParityFont(style, bold, italic);
            if (font != null) {
                p.setFont(font);
            } else if (bold || italic) {
                // Registry couldn't mint a font — degrade to the synthetic style so
                // the bold/italic attribute isn't silently lost.
                if (bold) p.setBold();
                if (italic) p.setItalic();
            }
            float lineHeight = (float) style.path("lineHeight").asDouble(DEFAULT_LINE_HEIGHT);
            p.setMultipliedLeading(lineHeight);
        } else {
            if (bold) p.setBold();
            if (italic) p.setItalic();
        }
        Color fontColor = parseCssColorToItext(style.path("color").asText(""));
        if (fontColor != null) {
            p.setFontColor(fontColor);
        }
        // Element-level underline / strikethrough. iText's Paragraph.setUnderline
        // lays a stroke UNDER every text run the paragraph holds (whether
        // plain content or rich-run Text children). Negative y-offset draws
        // below the baseline (underline); a positive y-offset drawn at
        // ~fontSize × 0.27 lands on the x-height midline (strikethrough).
        if (style.path("underline").asBoolean(false)) {
            p.setUnderline(0.75f, -2f);
        }
        if (style.path("strikethrough").asBoolean(false)) {
            p.setUnderline(0.75f, fontSize * 0.27f);
        }
        String align = style.path("align").asText("left").toLowerCase();
        p.setTextAlignment(switch (align) {
            case "center" -> TextAlignment.CENTER;
            case "right" -> TextAlignment.RIGHT;
            default -> TextAlignment.LEFT;
        });
    }


    private String substitute(String template, JsonNode globalData, JsonNode rowContext) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher m = VAR_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String val = lookup(key, globalData, rowContext);
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String lookup(String path, JsonNode globalData, JsonNode rowContext) {
        JsonNode n = resolveDataPath(globalData, path);
        if ((n == null || n.isMissingNode() || n.isNull()) && rowContext != null) {
            n = resolveDataPath(rowContext, path);
        }
        if (n == null || n.isMissingNode() || n.isNull()) {
            return "";
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isNumber()) {
            return n.asText();
        }
        if (n.isBoolean()) {
            return Boolean.toString(n.asBoolean());
        }
        return n.toString();
    }

    private JsonNode resolveDataPath(JsonNode root, String path) {
        if (root == null || path == null || path.isEmpty()) {
            return null;
        }
        String[] parts = path.split("\\.");
        JsonNode cur = root;
        for (String part : parts) {
            if (cur == null || !cur.has(part)) {
                return null;
            }
            cur = cur.get(part);
        }
        return cur;
    }

    private PageSpec readPage(JsonNode layoutJson) {
        JsonNode page = layoutJson.path("page");
        String size = page.path("size").asText("A4").toUpperCase();
        float uniform = (float) page.path("margin").asDouble(40);
        JsonNode m = page.path("margins");
        float top;
        float right;
        float bottom;
        float left;
        if (m.isObject()) {
            top = (float) m.path("top").asDouble(uniform);
            right = (float) m.path("right").asDouble(uniform);
            bottom = (float) m.path("bottom").asDouble(uniform);
            left = (float) m.path("left").asDouble(uniform);
        } else {
            top = right = bottom = left = uniform;
        }
        PageSize ps = switch (size) {
            case "LETTER" -> PageSize.LETTER;
            case "A3" -> PageSize.A3;
            case "A5" -> PageSize.A5;
            default -> PageSize.A4;
        };
        return new PageSpec(ps, top, right, bottom, left);
    }

    private record PageSpec(PageSize pageSize, float marginTop, float marginRight, float marginBottom, float marginLeft) {
    }

    // ── Parity measurement hook ────────────────────────────────────────────
    //
    // {@link LayoutMeasurementService} calls into these package-private methods
    // to get the exact height iText would consume for an element, before any
    // bytes are written. The canvas then top-anchors text identically and the
    // editor can soft-block save when content overflows its box.
    //
    // All measurement runs under the parity flag path so the font + leading
    // the canvas observes matches what the PDF will emit.

    /**
     * Measure a TEXT-like element (TEXT / PARAGRAPH / HEADER / FOOTER body) and
     * return the iText-laid-out total height in pt. Returns 0 when the element
     * isn't measurable (missing content, zero width).
     *
     * <p>Thin wrapper over {@link #measureTextElementLayout} kept so the
     * parity-mode top-anchor path in {@link #addText} stays compact when it
     * only needs the height.
     */
    float measureTextElementHeight(JsonNode element, JsonNode data) {
        return measureTextElementLayout(element, data).height();
    }

    /**
     * Measure a TEXT-like element and return both the total height AND the
     * per-line geometry iText laid out (one {@link MeasureResponse.TextLine}
     * per rendered line, with per-run advance widths). The canvas's
     * absolute-positioned line renderer consumes {@code lines}; the save-time
     * overflow check uses {@code height}.
     */
    ParagraphLayout measureTextElementLayout(JsonNode element, JsonNode data) {
        if (element == null || element.isNull()) return ParagraphLayout.empty();
        float width = (float) element.path("width").asDouble(0);
        if (width <= 0f) return ParagraphLayout.empty();

        JsonNode style = element.path("style");
        java.util.IdentityHashMap<Text, Integer> runIndex = new java.util.IdentityHashMap<>();
        Paragraph paragraph = buildParagraphFromContent(element.get("content"), data, null, style, runIndex);
        paragraph.setPadding(0);
        paragraph.setMargin(0);
        return measureParagraphLayout(paragraph, width, runIndex);
    }

    /**
     * Internal: run iText's layout engine against a throwaway Document and
     * return both the height AND per-line geometry for this paragraph at the
     * given width. Shared by {@link #measureTextElementLayout} (for the
     * frontend measurement endpoint) and the parity-mode {@code addText}
     * top-anchor path (which only reads the height).
     *
     * <p>Per-line harvesting walks {@link com.itextpdf.layout.renderer.ParagraphRenderer#getChildRenderers()}
     * — each child is a {@link com.itextpdf.layout.renderer.LineRenderer}.
     * Inside each line the grandchildren are {@link com.itextpdf.layout.renderer.TextRenderer}s
     * whose {@code getModelElement()} points back at the {@link Text} we
     * created. The identity-keyed {@code runIndex} map recovers the authored
     * run ordinal for each rendered shard.
     */
    private ParagraphLayout measureParagraphLayout(Paragraph paragraph, float width,
                                                    java.util.Map<Text, Integer> runIndex) {
        if (width <= 0f) return ParagraphLayout.empty();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (PdfDocument throwawayDoc = new PdfDocument(new PdfWriter(sink))) {
            // Tall page so text never hits the page boundary during layout.
            throwawayDoc.addNewPage(new PageSize(Math.max(width, 1f), 100000f));
            try (Document throwawayDocument = new Document(throwawayDoc)) {
                IRenderer rootRenderer = throwawayDocument.getRenderer();
                IRenderer paragraphRenderer = paragraph.createRendererSubTree().setParent(rootRenderer);
                LayoutResult result = paragraphRenderer.layout(
                        new LayoutContext(new LayoutArea(1, new Rectangle(width, 100000f))));
                if (result.getStatus() != LayoutResult.FULL || result.getOccupiedArea() == null) {
                    // PARTIAL / NOTHING: iText didn't finish layout (rare at
                    // our 100000pt height). Fall back to height-only so the
                    // caller still gets overflow info rather than 0.
                    float h = result.getOccupiedArea() == null ? 0f : result.getOccupiedArea().getBBox().getHeight();
                    return new ParagraphLayout(h, java.util.List.of());
                }
                float totalHeight = result.getOccupiedArea().getBBox().getHeight();
                // Per iText 7.2.5: the laid-out tree lives on `splitRenderer`
                // for status=FULL too — `paragraphRenderer` is pre-layout,
                // without child line renderers. `getSplitRenderer()` returns
                // the renderer whose children are the rendered LineRenderers.
                IRenderer laidOut = result.getSplitRenderer() != null ? result.getSplitRenderer() : paragraphRenderer;
                java.util.List<MeasureResponse.TextLine> lines = harvestLines(laidOut, totalHeight, runIndex);
                return new ParagraphLayout(totalHeight, lines);
            }
        } catch (Exception e) {
            log.warn("measureParagraphLayout failed: {}", e.getMessage());
            return ParagraphLayout.empty();
        }
    }

    /**
     * Composite result of the throwaway layout pass — total consumed height +
     * the per-line {@link MeasureResponse.TextLine} records the canvas's
     * absolute-positioned line renderer replays.
     */
    record ParagraphLayout(float height, java.util.List<MeasureResponse.TextLine> lines) {
        static ParagraphLayout empty() {
            return new ParagraphLayout(0f, java.util.List.of());
        }
    }

    /**
     * Phase 2.5 — measure the row heights a TABLE element needs to render
     * every cell without clipping. Returns a list starting with the header
     * row followed by one entry per body row in the same order the PDF
     * renderer emits. Values are in pt.
     *
     * <p>Cell width math: iText's {@code Table} uses percentage column widths
     * within the table's {@code setWidth} pt value. We replicate the same
     * normalisation so the paragraph measurement gets the exact width iText
     * will hand the cell at render time. Horizontal padding (4pt each side,
     * 8pt total) is subtracted; the measured paragraph height gets the
     * vertical padding (2pt × 2 under parity, 4pt × 2 legacy) added back so
     * the returned row heights already account for cell chrome.
     *
     * <p>Returns an empty list for malformed inputs (no columns, zero width,
     * missing element) so callers can fall back to the weight-based layout.
     */
    java.util.List<Float> measureTableRowHeights(JsonNode element, JsonNode data) {
        if (element == null || element.isNull()) return java.util.List.of();
        float tableWidth = (float) element.path("width").asDouble(0);
        if (tableWidth <= 0f) return java.util.List.of();
        JsonNode columns = element.path("columns");
        if (!columns.isArray() || columns.isEmpty()) return java.util.List.of();

        // Column weights → pt widths (same normalisation as addTable).
        JsonNode cwNode = element.path("columnWidths");
        float[] weights = new float[columns.size()];
        float sumW = 0f;
        for (int i = 0; i < columns.size(); i++) {
            float w = (cwNode.isArray() && i < cwNode.size())
                    ? (float) cwNode.get(i).asDouble(1)
                    : 1f;
            if (w <= 0f) w = 1f;
            weights[i] = w;
            sumW += w;
        }
        if (sumW <= 0f) return java.util.List.of();
        float[] colWidthPt = new float[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            colWidthPt[i] = tableWidth * (weights[i] / sumW);
        }

        // Resolve rows the same way addTable does: loop-mode pulls from data,
        // static-mode synthesises N empty row objects for preview parity.
        JsonNode rows;
        JsonNode dataKeyNode = element.get("dataKey");
        String dataKey = (dataKeyNode != null && dataKeyNode.isTextual()) ? dataKeyNode.asText("").trim() : "";
        if (!dataKey.isEmpty()) {
            JsonNode resolved = resolveDataPath(data, dataKey);
            if (resolved == null || !resolved.isArray()) resolved = data.path(dataKey);
            rows = resolved.isArray() ? resolved : JsonNodeFactory.instance.arrayNode();
        } else {
            int previewRows = 3;
            JsonNode prNode = element.get("tablePreviewBodyRows");
            if (prNode != null && prNode.isNumber()) {
                int parsed = prNode.asInt(3);
                if (parsed > 0) previewRows = Math.min(30, parsed);
            }
            com.fasterxml.jackson.databind.node.ArrayNode synth = JsonNodeFactory.instance.arrayNode();
            for (int i = 0; i < previewRows; i++) synth.add(JsonNodeFactory.instance.objectNode());
            rows = synth;
        }

        JsonNode elementStyle = element.path("style");
        float horizPaddingPerSide = 4f;       // matches addTable both legacy and parity
        float vertPadding = parityOn() ? 2f : 4f; // top + bottom match applyCellPadding

        // Header row — bold paragraphs built the same way addTable does.
        ObjectNode headerStyle = elementStyle.isObject()
                ? ((ObjectNode) elementStyle).deepCopy()
                : objectMapper.createObjectNode();
        headerStyle.put("bold", true);
        float headerMax = 0f;
        for (int ci = 0; ci < columns.size(); ci++) {
            JsonNode col = columns.get(ci);
            Paragraph p = buildParagraphFromContent(col.get("header"), data, null, headerStyle);
            p.setPadding(0);
            p.setMargin(0);
            float cellContentWidth = Math.max(1f, colWidthPt[ci] - 2f * horizPaddingPerSide);
            float h = measureParagraphLayout(p, cellContentWidth, null).height();
            headerMax = Math.max(headerMax, h);
        }
        java.util.List<Float> out = new java.util.ArrayList<>();
        out.add(roundTo2(headerMax + 2f * vertPadding));

        // Body rows.
        for (JsonNode row : rows) {
            if (row != null && row.isObject() && behaviourResolver.tableRowHidden(element.path("behaviour"), row, data)) {
                continue;
            }
            float rowMax = 0f;
            for (int ci = 0; ci < columns.size(); ci++) {
                JsonNode col = columns.get(ci);
                String key = col.path("key").asText("");
                JsonNode cellContent = row != null && row.has(key) ? row.get(key) : null;
                Paragraph p = buildParagraphFromContent(cellContent, data, row, elementStyle);
                p.setPadding(0);
                p.setMargin(0);
                float cellContentWidth = Math.max(1f, colWidthPt[ci] - 2f * horizPaddingPerSide);
                float h = measureParagraphLayout(p, cellContentWidth, null).height();
                rowMax = Math.max(rowMax, h);
            }
            out.add(roundTo2(rowMax + 2f * vertPadding));
        }
        return out;
    }

    private static float roundTo2(float v) {
        return Math.round(v * 100f) / 100f;
    }

    /**
     * Walk the laid-out {@code ParagraphRenderer} tree and pull per-line +
     * per-run geometry into the wire-format records the frontend consumes.
     * Each paragraph child is a {@code LineRenderer}; each line grandchild is
     * a {@code TextRenderer} whose model element is the {@link Text} we put
     * in during {@code paragraphFromRuns}. The identity-keyed
     * {@code runIndex} map gives us the authored run ordinal for every shard
     * (wrap-split fragments share a model reference, so the lookup Just Works).
     *
     * <p>Coordinates: iText's occupied-area bbox is bottom-origin within the
     * throwaway document. We flip to top-origin (y=0 at the paragraph's first
     * line) so the canvas can absolute-position each line with CSS {@code top}.
     */
    private java.util.List<MeasureResponse.TextLine> harvestLines(
            IRenderer paragraphRenderer,
            float totalHeight,
            java.util.Map<Text, Integer> runIndex) {
        if (!(paragraphRenderer instanceof com.itextpdf.layout.renderer.ParagraphRenderer pr)) {
            return java.util.List.of();
        }
        // iText 7.2.5's `ParagraphRenderer` holds the laid-out lines in a
        // protected `lines` field, not exposed via `getChildRenderers()`
        // (which returns the pre-layout TextRenderer children — a flat list).
        // Reflection is the narrow gateway to per-line geometry until iText
        // ships a public accessor. If reflection fails (future iText bump
        // renaming the field, security manager), we fall back to empty and
        // the canvas reverts to CSS flow.
        java.util.List<com.itextpdf.layout.renderer.LineRenderer> lineRenderers;
        try {
            java.lang.reflect.Field linesField =
                    com.itextpdf.layout.renderer.ParagraphRenderer.class.getDeclaredField("lines");
            linesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<com.itextpdf.layout.renderer.LineRenderer> raw =
                    (java.util.List<com.itextpdf.layout.renderer.LineRenderer>) linesField.get(pr);
            lineRenderers = raw;
        } catch (ReflectiveOperationException e) {
            log.warn("ParagraphRenderer.lines reflection failed — per-line harvest disabled: {}", e.getMessage());
            return java.util.List.of();
        }
        if (lineRenderers == null || lineRenderers.isEmpty()) {
            return java.util.List.of();
        }

        java.util.List<MeasureResponse.TextLine> out = new java.util.ArrayList<>();
        // iText coordinates are bottom-origin; we normalise to top-origin
        // relative to the paragraph start so the canvas can use CSS `top`.
        Float paragraphTopY = null;
        for (com.itextpdf.layout.renderer.LineRenderer lineRenderer : lineRenderers) {
            com.itextpdf.kernel.geom.Rectangle lineBox = lineRenderer.getOccupiedArea() == null
                    ? null
                    : lineRenderer.getOccupiedArea().getBBox();
            if (lineBox == null || lineBox.getHeight() <= 0f) continue;
            if (paragraphTopY == null) paragraphTopY = lineBox.getTop();
            float y = paragraphTopY - lineBox.getTop();
            float h = lineBox.getHeight();

            java.util.List<MeasureResponse.RunMeasurement> runs = new java.util.ArrayList<>();
            for (IRenderer textRenderer : lineRenderer.getChildRenderers()) {
                if (!(textRenderer instanceof com.itextpdf.layout.renderer.TextRenderer tr)) continue;
                com.itextpdf.kernel.geom.Rectangle runBox = tr.getOccupiedArea() == null
                        ? null
                        : tr.getOccupiedArea().getBBox();
                if (runBox == null) continue;
                Object model = tr.getModelElement();
                int ordinal = -1;
                if (runIndex != null && model instanceof Text t) {
                    Integer ord = runIndex.get(t);
                    if (ord != null) ordinal = ord;
                }
                String rendered = tr.getText() == null ? "" : tr.getText().toString();
                runs.add(new MeasureResponse.RunMeasurement(rendered, runBox.getWidth(), ordinal));
            }
            out.add(new MeasureResponse.TextLine(y, h, runs));
        }
        if (!out.isEmpty()) {
            MeasureResponse.TextLine last = out.get(out.size() - 1);
            if (last.y() + last.h() > totalHeight + 0.5f) {
                return java.util.List.of();
            }
        }
        return out;
    }

    /**
     * Parity-mode cell padding: matches the canvas {@code px-1 py-0.5} (4px
     * horizontal, 2px vertical) rather than iText's default {@code .setPadding(4)}
     * (4pt on all sides). Off the flag we keep the legacy uniform 4pt so
     * existing layouts don't shift.
     */
    private Cell applyCellPadding(Cell cell) {
        if (parityOn()) {
            return cell.setPaddingTop(2f).setPaddingBottom(2f).setPaddingLeft(4f).setPaddingRight(4f);
        }
        return cell.setPadding(4);
    }

    /**
     * Parity-mode cell border: 1pt solid zinc-400 (#a1a1aa) to match the
     * canvas CSS. Off the flag we leave iText's default border in place
     * (0.5pt black) so legacy renders don't change.
     */
    private Cell applyCellBorderForParity(Cell cell) {
        if (!parityOn()) return cell;
        Color zinc400 = new DeviceRgb(0xa1, 0xa1, 0xaa);
        com.itextpdf.layout.borders.Border border =
                new com.itextpdf.layout.borders.SolidBorder(zinc400, 1f);
        cell.setBorder(border);
        return cell;
    }

    /** True when an element type renders via the text path (addText / addList dispatch). */
    static boolean isTextLikeType(String type) {
        if (type == null) return true; // default dispatch is TEXT
        return switch (type) {
            case "TEXT", "PARAGRAPH", "HEADER", "FOOTER" -> true;
            default -> false;
        };
    }
}
