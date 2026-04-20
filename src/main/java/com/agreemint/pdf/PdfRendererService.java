package com.agreemint.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.io.image.ImageData;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.PdfArray;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.action.PdfAction;
import com.itextpdf.kernel.pdf.annot.PdfLinkAnnotation;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Link;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.itextpdf.layout.properties.VerticalAlignment;
import com.itextpdf.layout.renderer.CellRenderer;
import com.itextpdf.layout.renderer.DrawContext;
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

    private final ObjectMapper objectMapper;
    private final LayoutBehaviourResolver behaviourResolver;

    public PdfRendererService(ObjectMapper objectMapper, LayoutBehaviourResolver behaviourResolver) {
        this.objectMapper = objectMapper;
        this.behaviourResolver = behaviourResolver;
    }

    public byte[] render(JsonNode layoutJson, JsonNode data) throws IOException {
        PageSpec pageSpec = readPage(layoutJson);
        List<JsonNode> perPageElements = pageElementArraysFromLayout(layoutJson);

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
                if (("HEADER".equals(type) || "FOOTER".equals(type))
                        && drawEl.path("bandElements").isArray()
                        && drawEl.path("bandElements").size() > 0) {
                    renderBandChildren(pdfDoc, document, drawEl, pageData, pageHeight, pageNumber);
                    continue;
                }
                dispatchElementByType(pdfDoc, document, drawEl, type, pageData, pageHeight, pageNumber);
            }
        }

        document.close();
        return baos.toByteArray();
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
    }

    private void addText(PdfDocument pdfDoc, Document document, JsonNode el, JsonNode data, float pageHeight, int pageNumber) {
        float x = (float) el.path("x").asDouble(0);
        float elY = (float) el.path("y").asDouble(0);
        float w = (float) el.path("width").asDouble(200);
        float h = (float) el.path("height").asDouble(20);
        float yTop = pageHeight - elY;
        float bottom = yTop - h;

        JsonNode style = el.path("style");
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
        p.setHeight(UnitValue.createPointValue(h));
        p.setVerticalAlignment(VerticalAlignment.TOP);
        p.setFixedPosition(pageNumber, x, bottom, w);
        document.add(p);
    }

    private Paragraph buildParagraphFromContent(JsonNode contentField, JsonNode data, JsonNode rowContext, JsonNode elementStyle) {
        JsonNode runs = resolveRichRuns(contentField);
        if (runs != null) {
            return paragraphFromRuns(runs, data, rowContext, elementStyle);
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
        Paragraph p = new Paragraph();
        if (elementStyle != null && !elementStyle.isNull()) {
            float fs = (float) elementStyle.path("fontSize").asDouble(12);
            p.setFontSize(fs);
            Color baseColor = parseCssColorToItext(elementStyle.path("color").asText(""));
            if (baseColor != null) {
                p.setFontColor(baseColor);
            }
        }
        for (JsonNode run : runs) {
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
        if (bold) {
            t.setBold();
        }
        if (italic) {
            t.setItalic();
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
        String dataKey = el.path("dataKey").asText("items");
        JsonNode rows = resolveDataPath(data, dataKey);
        if (rows == null || !rows.isArray()) {
            rows = data.path(dataKey);
        }
        if (!rows.isArray()) {
            rows = JsonNodeFactory.instance.arrayNode();
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
        Table table = new Table(UnitValue.createPercentArray(colWidths)).useAllAvailableWidth();

        JsonNode headerStyle = objectMapper.createObjectNode().put("bold", true);
        int headerColIndex = 0;
        for (JsonNode col : columns) {
            Paragraph headerParagraph = buildParagraphFromContent(col.get("header"), data, null, headerStyle);
            Cell headerCell = new Cell()
                    .add(headerParagraph)
                    .setVerticalAlignment(VerticalAlignment.MIDDLE)
                    .setPadding(4);
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
                String cellText = cellValue(row, key, data);
                Paragraph para = new Paragraph(cellText);
                LayoutBehaviourResolver.CellStyleDelta delta =
                        behaviourResolver.tableCellStyle(behaviour, row, data, bodyColIndex);
                if (delta.textColor() != null && !delta.textColor().isBlank()) {
                    Color tc = parseCssColorToItext(delta.textColor());
                    if (tc != null) {
                        para.setFontColor(tc);
                    }
                }
                Cell bodyCell = new Cell()
                        .add(para)
                        .setVerticalAlignment(VerticalAlignment.MIDDLE)
                        .setPadding(4);
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

        table.setMarginTop((float) el.path("marginTop").asDouble(8));
        table.setMarginBottom((float) el.path("marginBottom").asDouble(8));

        float x = (float) el.path("x").asDouble(0);
        float elY = (float) el.path("y").asDouble(0);
        float w = (float) el.path("width").asDouble(0);
        if (w <= 0f) {
            w = document.getPdfDocument().getDefaultPageSize().getWidth() - x;
        }
        float h = (float) el.path("height").asDouble(120);
        float bottom = pageHeight - elY - h;
        table.setFixedPosition(pageNumber, x, bottom, w);
        document.add(table);
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

    /** Fill precedence: cell > column > row (matches frontend). Keys: cell="row,col", col="0"…, row="-1","0"… */
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
        Canvas listCanvas = new Canvas(new PdfCanvas(pdfDoc.getPage(pageNumber)),
                new Rectangle(x, bottom, w, h));

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

            final String effectiveBulletStyle = effectiveBulletStyleFor(listStyleStr, level);
            String textMarker = textMarkerFor(listStyleStr, level, groupIndex, startNumber);
            String itemText = substitute(row.text, data, null);

            float rowLeftIndent = level * indent;
            // The row's own width inside the list is (w − rowLeftIndent).
            // Column 1 = markerColWidth (fixed pt). Column 2 = remainder.
            float textColWidth = Math.max(1f, w - rowLeftIndent - markerColWidth);

            Table rowTable = new Table(UnitValue.createPointArray(new float[]{ markerColWidth, textColWidth }));
            rowTable.setBorder(com.itextpdf.layout.borders.Border.NO_BORDER);
            rowTable.setMarginTop(i == 0 ? 0 : itemSpacing);
            rowTable.setMarginBottom(0);
            rowTable.setMarginLeft(rowLeftIndent);
            rowTable.setWidth(UnitValue.createPointValue(rowLeftIndent + markerColWidth + textColWidth));

            // ── Marker cell ────────────────────────────────────────────
            Cell markerCell = new Cell();
            markerCell.setBorder(com.itextpdf.layout.borders.Border.NO_BORDER);
            markerCell.setPadding(0);
            markerCell.setPaddingRight(2);
            markerCell.setVerticalAlignment(VerticalAlignment.TOP);

            if (effectiveBulletStyle != null) {
                // Shape marker — custom renderer draws a circle/square/dash
                // on PdfCanvas after the cell's final position is known.
                final float markerFontSize = fontSize;
                final float markerLineHeight = lineHeight;
                final Color markerColor = textColor;
                markerCell.setNextRenderer(new ShapeMarkerCellRenderer(
                        markerCell, effectiveBulletStyle, markerFontSize, markerLineHeight, markerColor));
            } else if (!textMarker.isEmpty()) {
                // Ordered marker — plain Paragraph in the cell. Right-aligned
                // so "1.", "10.", "100." all terminate at the same column.
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
            // The marker aligns with the FIRST line of the row. Optical centre
            // of lowercase text sits ≈ linePadTop + fontSize × 0.47 below the
            // line box top; the first line box sits at the cell's top edge.
            float rowH = fontSize * lineHeight;
            float linePadTop = (rowH - fontSize) * 0.5f;
            float cy = bbox.getTop() - linePadTop - fontSize * 0.47f;
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
            img.setFixedPosition(pageNumber, x, bottom);
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

        Color strokeColor = parseCssColorToItext(el.path("style").path("color").asText(""));
        if (strokeColor == null) {
            strokeColor = ColorConstants.BLACK;
        }

        PdfCanvas canvas = new PdfCanvas(pdfDoc.getPage(pageNumber));
        canvas.saveState();
        canvas.setStrokeColor(strokeColor);
        canvas.setLineWidth(Math.max(0.25f, stroke));
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

        PdfCanvas canvas = new PdfCanvas(pdfDoc.getPage(pageNumber));
        canvas.saveState();
        if (fill != null) {
            canvas.setFillColor(fill);
            canvas.rectangle(rect);
            canvas.fill();
        }
        canvas.setStrokeColor(stroke);
        canvas.setLineWidth(2);
        canvas.setLineDash(3f, 2f);
        canvas.rectangle(rect);
        canvas.stroke();
        canvas.restoreState();
    }

    /** Layout Y measured from top of page → iText PDF Y (bottom-origin). */
    private float layoutYToPdf(float pageHeight, float yFromTop) {
        return pageHeight - yFromTop;
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
        int seg = 40;
        PdfCanvas canvas = new PdfCanvas(pdfDoc.getPage(pageNumber));
        canvas.saveState();
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
        int seg = 36;
        PdfCanvas canvas = new PdfCanvas(pdfDoc.getPage(pageNumber));
        canvas.saveState();
        if (fill != null) {
            ellipseRingPath(canvas, pageHeight, x, elY, w, h, seg);
            ellipseRingPath(canvas, pageHeight, ox, oy, iw, ih, seg);
            canvas.setFillColor(fill);
            canvas.eoFill();
        }
        if (sw >= 0.25f) {
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
        float py0 = layoutYToPdf(pageHeight, ysTop[0]);
        canvas.moveTo(xsTop[0], py0);
        for (int i = 1; i < xsTop.length; i++) {
            canvas.lineTo(xsTop[i], layoutYToPdf(pageHeight, ysTop[i]));
        }
        canvas.closePath();
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
            return;
        }
        float fontSize = (float) style.path("fontSize").asDouble(12);
        p.setFontSize(fontSize);
        if (style.path("bold").asBoolean(false)) {
            p.setBold();
        }
        if (style.path("italic").asBoolean(false)) {
            p.setItalic();
        }
        Color fontColor = parseCssColorToItext(style.path("color").asText(""));
        if (fontColor != null) {
            p.setFontColor(fontColor);
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
}
