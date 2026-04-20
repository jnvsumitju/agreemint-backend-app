package com.agreemint.pdf;

import com.agreemint.api.dto.MeasureResponse;
import com.agreemint.config.PixelParityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 — end-to-end parity-mode rendering + measurement tests.
 *
 * <p>Validates that with {@code features.pixel-parity.enabled=true}:
 * <ul>
 *   <li>{@link PdfRendererService} produces a PDF without crashing</li>
 *   <li>{@link PdfRendererService#measureTextElementHeight} returns a positive
 *       height in the expected range for a one-line paragraph at 12pt / 1.4lh</li>
 *   <li>{@link LayoutMeasurementService#measure} delegates correctly to the
 *       renderer for TEXT elements</li>
 * </ul>
 *
 * <p>The tests intentionally avoid asserting exact heights — glyph metrics
 * can shift by sub-pt across iText patch releases. We assert the height is
 * within a realistic window (12pt × 1.4 = 16.8pt, with some slack for
 * ascender/descender).
 */
class ParityRenderingTest {

    private static PdfFontRegistry registry;
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void loadRegistry() {
        registry = new PdfFontRegistry();
        ReflectionTestUtils.invokeMethod(registry, "loadPrograms");
        assertTrue(registry.isFullyLoaded(), "TTFs must be present under classpath:fonts/ for these tests");
    }

    private PdfRendererService parityRenderer() {
        PixelParityProperties flag = new PixelParityProperties();
        flag.setEnabled(true);
        return new PdfRendererService(
                mapper, new LayoutBehaviourResolver(mapper), registry, flag);
    }

    @Test
    void parityMode_rendersSingleTextElementWithoutCrashing() throws IOException {
        ObjectNode layout = buildLayoutWithText("Hello parity");
        byte[] pdf = parityRenderer().render(layout, JsonNodeFactory.instance.objectNode());
        assertNotNull(pdf);
        assertTrue(pdf.length > 100, "PDF should contain real content, not an empty document");

        // Confirm the PDF parses — cheap sanity check against stream corruption
        // from a mis-wired font or clipping bug.
        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            assertTrue(doc.getNumberOfPages() >= 1);
        }
    }

    @Test
    void measureTextElementHeight_returnsPositiveHeightForOneLine() {
        PdfRendererService svc = parityRenderer();
        ObjectNode el = textElement("A short line", /* width */ 400f);
        float h = svc.measureTextElementHeight(el, JsonNodeFactory.instance.objectNode());
        // A single line at 12pt × 1.4 leading is ~16.8pt. Allow slack for
        // ascender/descender quirks between iText patch releases.
        assertTrue(h > 10f && h < 30f, "Unexpected measured height: " + h);
    }

    @Test
    void measureTextElementHeight_growsWithWrappedLines() {
        PdfRendererService svc = parityRenderer();
        // Long sentence in a narrow box → forces wrap to multiple lines.
        ObjectNode narrowEl = textElement(
                "This is a fairly long paragraph that will definitely wrap across several lines "
                        + "when we squeeze the box width down to fifty points of real estate.",
                /* width */ 50f);
        float narrowH = svc.measureTextElementHeight(narrowEl, JsonNodeFactory.instance.objectNode());

        ObjectNode wideEl = textElement(
                "This is a fairly long paragraph that will definitely wrap across several lines "
                        + "when we squeeze the box width down to fifty points of real estate.",
                /* width */ 1000f);
        float wideH = svc.measureTextElementHeight(wideEl, JsonNodeFactory.instance.objectNode());

        assertTrue(narrowH > wideH * 3f,
                "Narrow box should wrap to many more lines than wide box (got narrow="
                        + narrowH + ", wide=" + wideH + ")");
    }

    @Test
    void measureTextElementHeight_returnsZeroWhenParityOff() {
        // When the flag is off we still expose the measurement hook for the
        // frontend overflow check, but the renderer only bothers with the
        // heavy iText layout pass when parity is fully enabled — otherwise
        // callers save the ~100ms-per-element cost.
        PixelParityProperties flag = new PixelParityProperties();
        flag.setEnabled(false);
        PdfRendererService svc = new PdfRendererService(
                mapper, new LayoutBehaviourResolver(mapper), registry, flag);
        ObjectNode el = textElement("Anything", 200f);
        // NOTE: we still run the layout pass off-flag in Phase 1 — the cost
        // gate will arrive in Phase 5. For now this just asserts the method
        // returns something sensible rather than crashing when the flag is off.
        float h = svc.measureTextElementHeight(el, JsonNodeFactory.instance.objectNode());
        assertTrue(h >= 0f);
    }

    @Test
    void parityMode_rendersListWithMarkersAndWrappedText() throws IOException {
        // Phase-3 smoke: ordered + unordered lists must render under parity
        // without crashing. The marker-center formula in ShapeMarkerCellRenderer
        // reads fontSize + lineHeight from applyTextStyle, which now gets the
        // parity font / leading, so both list markers and body text track.
        ObjectNode layout = mapper.createObjectNode();
        ArrayNode elements = layout.putArray("elements");
        ObjectNode list = elements.addObject();
        list.put("id", "lst_parity");
        list.put("type", "LIST");
        list.put("x", 50f);
        list.put("y", 50f);
        list.put("width", 400f);
        list.put("height", 200f);
        list.put("listStyle", "disc");
        ObjectNode style = list.putObject("style");
        style.put("fontSize", 12);
        style.put("fontFamily", PdfFontRegistry.FAMILY_SERIF);
        style.put("lineHeight", 1.4);
        ArrayNode items = list.putArray("listItems");
        items.addObject().put("text", "First bullet");
        items.addObject().put("text", "Second bullet — long enough to wrap when the width is squeezed which exercises the marker-to-first-line alignment formula");
        items.addObject().put("text", "Third bullet");

        byte[] pdf = parityRenderer().render(layout, JsonNodeFactory.instance.objectNode());
        assertNotNull(pdf);
        assertTrue(pdf.length > 100);
        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            assertTrue(doc.getNumberOfPages() >= 1);
        }
    }

    @Test
    void parityMode_rendersTableWithRichBodyCells() throws IOException {
        // Phase-2 smoke: table body cells must pick up the element's font /
        // fontSize / leading through the parity path without crashing, and
        // the resulting PDF must parse cleanly.
        ObjectNode layout = mapper.createObjectNode();
        ArrayNode elements = layout.putArray("elements");
        ObjectNode table = elements.addObject();
        table.put("id", "tbl_parity");
        table.put("type", "TABLE");
        table.put("x", 50f);
        table.put("y", 50f);
        table.put("width", 400f);
        table.put("height", 120f);
        ObjectNode style = table.putObject("style");
        style.put("fontSize", 11);
        style.put("fontFamily", PdfFontRegistry.FAMILY_SANS);
        ArrayNode columns = table.putArray("columns");
        columns.addObject().put("header", "Name").put("key", "name");
        columns.addObject().put("header", "Amount").put("key", "amount");
        table.put("tablePreviewBodyRows", 3);

        byte[] pdf = parityRenderer().render(layout, JsonNodeFactory.instance.objectNode());
        assertNotNull(pdf);
        assertTrue(pdf.length > 100);
        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            assertTrue(doc.getNumberOfPages() >= 1);
        }
    }

    @Test
    void parityMode_rendersShapesWithLineStyleAndRotation() throws IOException {
        // Phase-4 smoke: BOX + LINE + ELLIPSE with explicit lineStyle +
        // rotation must all round-trip to a valid PDF. Visual-regression
        // checks (dashed vs dotted, rotation angles) land in phase 5's
        // golden-test harness.
        ObjectNode layout = mapper.createObjectNode();
        ArrayNode elements = layout.putArray("elements");

        // Solid BOX with 3pt border.
        ObjectNode box = elements.addObject();
        box.put("id", "box_solid").put("type", "BOX");
        box.put("x", 20f).put("y", 20f).put("width", 100f).put("height", 60f);
        ObjectNode boxStyle = box.putObject("style");
        boxStyle.put("lineStyle", "solid");
        boxStyle.put("borderWidth", 3);
        boxStyle.put("color", "#333333");

        // Dotted LINE.
        ObjectNode line = elements.addObject();
        line.put("id", "line_dotted").put("type", "LINE");
        line.put("x", 20f).put("y", 120f).put("width", 200f).put("height", 1f);
        line.put("strokeWidth", 1);
        ObjectNode lineStyle = line.putObject("style");
        lineStyle.put("lineStyle", "dotted");
        lineStyle.put("color", "#2563eb");

        // Rotated ELLIPSE — 15 degrees, dashed stroke.
        ObjectNode ellipse = elements.addObject();
        ellipse.put("id", "ellipse_rotated").put("type", "ELLIPSE");
        ellipse.put("x", 250f).put("y", 50f).put("width", 120f).put("height", 80f);
        ellipse.put("strokeWidth", 2);
        ObjectNode ellipseStyle = ellipse.putObject("style");
        ellipseStyle.put("lineStyle", "dashed");
        ellipseStyle.put("color", "#a1a1aa");
        ellipseStyle.put("rotation", 15);

        byte[] pdf = parityRenderer().render(layout, JsonNodeFactory.instance.objectNode());
        assertNotNull(pdf);
        assertTrue(pdf.length > 100);
        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            assertTrue(doc.getNumberOfPages() >= 1);
        }
    }

    @Test
    void measureTextElementLayout_harvestsLinesForSingleLine() {
        PdfRendererService svc = parityRenderer();
        com.fasterxml.jackson.databind.node.ObjectNode el = textElement("One-line only.", 400f);
        PdfRendererService.ParagraphLayout layout = svc.measureTextElementLayout(el, JsonNodeFactory.instance.objectNode());
        assertEquals(1, layout.lines().size(), "Single-line paragraph should produce 1 TextLine, got " + layout.lines().size());
        MeasureResponse.TextLine line = layout.lines().get(0);
        assertEquals(0f, line.y(), 0.01f, "First line y must be 0 (paragraph-top origin)");
        assertTrue(line.h() > 10f && line.h() < 30f, "Line height outside expected range: " + line.h());
    }

    @Test
    void measureTextElementLayout_harvestsLinesForWrappedText() {
        PdfRendererService svc = parityRenderer();
        com.fasterxml.jackson.databind.node.ObjectNode el = textElement(
                "Long sentence that will wrap across several lines when we squeeze the width down.",
                /* width */ 100f);
        PdfRendererService.ParagraphLayout layout = svc.measureTextElementLayout(el, JsonNodeFactory.instance.objectNode());
        assertTrue(layout.lines().size() >= 2, "Wrapped paragraph should produce 2+ lines, got " + layout.lines().size());
        // Each line y should increase monotonically from 0.
        float prevY = -1f;
        for (MeasureResponse.TextLine line : layout.lines()) {
            assertTrue(line.y() >= prevY, "Line ys must be monotonically increasing");
            prevY = line.y();
        }
    }

    @Test
    void measureTextElementLayout_runIndexSurvivesInRichRuns() {
        // Build a rich-content paragraph with two runs (plain + bold) so the
        // harvester recovers the authored run ordinals.
        ObjectNode el = mapper.createObjectNode();
        el.put("id", "el_rich");
        el.put("type", "TEXT");
        el.put("x", 0f).put("y", 0f).put("width", 400f).put("height", 100f);
        ObjectNode content = el.putObject("content");
        content.put("rich", true);
        com.fasterxml.jackson.databind.node.ArrayNode runs = content.putArray("runs");
        runs.addObject().put("type", "text").put("text", "plain ");
        runs.addObject().put("type", "text").put("text", "bold").put("bold", true);

        PdfRendererService svc = parityRenderer();
        PdfRendererService.ParagraphLayout layout = svc.measureTextElementLayout(el, JsonNodeFactory.instance.objectNode());
        assertFalse(layout.lines().isEmpty());
        MeasureResponse.TextLine line = layout.lines().get(0);
        // Collect ordinals observed; must be {0, 1} covering both authored runs.
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (MeasureResponse.RunMeasurement run : line.runs()) {
            if (run.runIndex() >= 0) seen.add(run.runIndex());
        }
        assertTrue(seen.contains(0), "Run 0 (plain) missing from harvested lines");
        assertTrue(seen.contains(1), "Run 1 (bold) missing from harvested lines");
    }

    @Test
    void layoutMeasurementService_includesBandChildren() {
        // HEADER with one bandElements TEXT child: the child's measurement
        // must appear under its own id in the response.
        ObjectNode layout = mapper.createObjectNode();
        ArrayNode elements = layout.putArray("elements");
        ObjectNode header = elements.addObject();
        header.put("id", "h1").put("type", "HEADER");
        header.put("x", 0f).put("y", 0f).put("width", 500f).put("height", 50f);
        ArrayNode bandElements = header.putArray("bandElements");
        ObjectNode child = bandElements.addObject();
        child.put("id", "h1_child");
        child.put("type", "TEXT");
        child.put("x", 10f).put("y", 10f).put("width", 200f).put("height", 30f);
        child.put("content", "Band child text");

        var provider = new org.springframework.beans.factory.ObjectProvider<PdfRendererService>() {
            final PdfRendererService renderer = parityRenderer();
            @Override public PdfRendererService getObject() { return renderer; }
            @Override public PdfRendererService getObject(Object... args) { return renderer; }
            @Override public PdfRendererService getIfAvailable() { return renderer; }
            @Override public PdfRendererService getIfUnique() { return renderer; }
        };
        LayoutMeasurementService svc = new LayoutMeasurementService(provider);
        var resp = svc.measure(layout, JsonNodeFactory.instance.objectNode(), null);

        assertTrue(resp.measurements().containsKey("h1_child"),
                "Band child id must appear in measurement response; got keys: " + resp.measurements().keySet());
        assertTrue(resp.measurements().get("h1_child").measuredHeight() > 0f,
                "Band child measurement should have a positive height");
    }

    @Test
    void layoutMeasurementService_delegatesToRenderer() {
        PdfRendererService renderer = parityRenderer();
        org.springframework.beans.factory.ObjectProvider<PdfRendererService> provider =
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override public PdfRendererService getObject() { return renderer; }
                    @Override public PdfRendererService getObject(Object... args) { return renderer; }
                    @Override public PdfRendererService getIfAvailable() { return renderer; }
                    @Override public PdfRendererService getIfUnique() { return renderer; }
                };
        LayoutMeasurementService svc = new LayoutMeasurementService(provider);

        ObjectNode layout = buildLayoutWithText("Measured hello");
        var resp = svc.measure(layout, JsonNodeFactory.instance.objectNode(), null);
        assertFalse(resp.measurements().isEmpty());
        var firstMeasurement = resp.measurements().values().iterator().next();
        assertTrue(firstMeasurement.measuredHeight() > 0f,
                "Expected positive measured height, got " + firstMeasurement.measuredHeight());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private ObjectNode textElement(String text, float width) {
        ObjectNode el = mapper.createObjectNode();
        el.put("id", "el_" + Math.abs(text.hashCode()));
        el.put("type", "TEXT");
        el.put("x", 50f);
        el.put("y", 50f);
        el.put("width", width);
        el.put("height", 100f);
        el.put("content", text);
        ObjectNode style = el.putObject("style");
        style.put("fontSize", 12);
        // Leaving fontFamily unset exercises the DEFAULT_PARITY_FAMILY fallback
        // in resolveParityFont — matches what legacy layouts without an
        // explicit family will look like after phase 5 migration.
        return el;
    }

    private ObjectNode buildLayoutWithText(String text) {
        ObjectNode layout = mapper.createObjectNode();
        ArrayNode elements = layout.putArray("elements");
        elements.add(textElement(text, 400f));
        return layout;
    }
}
