package com.agreemint.pdf;

import com.agreemint.config.PixelParityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Underline and strikethrough resolve per run the way bold and italic do.
 *
 * <p>Three states, and the third is the one that was missing: a run that omits
 * the key inherits the element, a run that sets it {@code true} turns it on,
 * and a run that sets it {@code false} turns it OFF against an element that has
 * it on. Bold and italic have always worked this way; these two read only their
 * own value, so an underlined text box lost its underline the moment its
 * content became rich runs — which is the moment anyone types in it.
 *
 * <p>The canvas has always used {@code r.underline ?? elementUnderline}, so
 * this was a canvas-versus-PDF divergence rather than a missing feature: the
 * editor showed the underline and the document did not.
 *
 * <p>Asserted on the drawing operators, because a decoration is a stroked line
 * and not part of the text. That also lets the offset be checked, which matters
 * for strikethrough: the run path used a flat 3.2pt, which is 12 × 0.27 — right
 * at the default size and steadily wronger above it.
 */
class RunDecorationTriStateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static PdfFontRegistry registry;

    /** Text baseline: {@code <x> <y> Td}. */
    private static final Pattern BASELINE = Pattern.compile("([0-9.]+)\\s+([0-9.]+)\\s+Td");
    /** Start of a stroked decoration line: {@code <x> <y> m}. */
    private static final Pattern STROKE = Pattern.compile("([0-9.]+)\\s+([0-9.]+)\\s+m\\b");

    @BeforeAll
    static void loadFonts() {
        registry = new PdfFontRegistry();
        ReflectionTestUtils.invokeMethod(registry, "loadPrograms");
    }

    private static PdfRendererService renderer() {
        PixelParityProperties flag = new PixelParityProperties();
        flag.setEnabled(true);
        return new PdfRendererService(
                MAPPER, new LayoutBehaviourResolver(MAPPER), registry, flag, "https://crixaa.test");
    }

    /**
     * @param elementMark  mark set on the element style, or null to omit
     * @param runMark      explicit value on the run, or null to omit the key
     */
    private static byte[] render(String key, Boolean elementMark, Boolean runMark,
                                 int fontSize, String linkHref) throws Exception {
        ObjectNode run = MAPPER.createObjectNode();
        run.put("type", "text").put("text", "Sample");
        if (runMark != null) run.put(key, runMark.booleanValue());
        if (linkHref != null) run.put("linkHref", linkHref);

        ObjectNode content = MAPPER.createObjectNode();
        content.put("rich", true);
        content.putArray("runs").add(run);

        ObjectNode el = MAPPER.createObjectNode();
        el.put("id", "x").put("type", "TEXT")
                .put("x", 40).put("y", 100).put("width", 300).put("height", 60);
        el.set("content", content);
        ObjectNode style = el.putObject("style");
        style.put("fontSize", fontSize).put("fontFamily", "Inter").put("color", "#111827");
        if (elementMark != null) style.put(key, elementMark.booleanValue());

        ObjectNode layout = MAPPER.createObjectNode();
        layout.putObject("page").put("size", "A4");
        layout.putArray("pages").addObject().putArray("elements").add(el);
        return renderer().render((JsonNode) layout, MAPPER.createObjectNode());
    }

    private static String stream(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new String(doc.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    /** Y of every stroked decoration line, relative to the text baseline. */
    private static List<Float> decorationOffsets(byte[] pdf) throws Exception {
        String s = stream(pdf);
        Matcher b = BASELINE.matcher(s);
        assertTrue(b.find(), "the text itself must be drawn");
        float baseline = Float.parseFloat(b.group(2));

        List<Float> out = new ArrayList<>();
        Matcher m = STROKE.matcher(s);
        while (m.find()) out.add(Float.parseFloat(m.group(2)) - baseline);
        return out;
    }

    private static int decorations(byte[] pdf) throws Exception {
        return decorationOffsets(pdf).size();
    }

    // ── underline ─────────────────────────────────────────────────────────────

    @Test
    void aRunInheritsTheElementsUnderline() throws Exception {
        // The regression this fixes: rich content dropped the element's
        // underline entirely, because the rich-run path returns before
        // applyTextStyle ever stamps it on the paragraph.
        assertEquals(1, decorations(render("underline", true, null, 12, null)),
                "a run that says nothing about underline must inherit the element's");
    }

    @Test
    void aRunCanTurnTheElementsUnderlineOff() throws Exception {
        assertEquals(0, decorations(render("underline", true, false, 12, null)),
                "an explicit false must win over the element, the way bold already does");
    }

    @Test
    void aRunCanTurnUnderlineOnForItself() throws Exception {
        assertEquals(1, decorations(render("underline", null, true, 12, null)));
    }

    @Test
    void nothingIsDrawnWhenNeitherSetsIt() throws Exception {
        assertEquals(0, decorations(render("underline", null, null, 12, null)));
    }

    @Test
    void theUnderlineSitsBelowTheBaseline() throws Exception {
        List<Float> offsets = decorationOffsets(render("underline", null, true, 12, null));
        assertEquals(-2.0f, offsets.get(0), 0.01f, "underline draws below the baseline");
    }

    // ── strikethrough ─────────────────────────────────────────────────────────

    @Test
    void aRunInheritsTheElementsStrikethrough() throws Exception {
        assertEquals(1, decorations(render("strikethrough", true, null, 12, null)));
    }

    @Test
    void aRunCanTurnTheElementsStrikethroughOff() throws Exception {
        assertEquals(0, decorations(render("strikethrough", true, false, 12, null)));
    }

    @Test
    void theStrikethroughOffsetScalesWithTheRunsSize() throws Exception {
        // A flat 3.2pt is 12 × 0.27 — correct at the default size only. At 24pt
        // the line sat 3.3pt low, under the glyphs instead of across them.
        float at12 = decorationOffsets(render("strikethrough", null, true, 12, null)).get(0);
        float at24 = decorationOffsets(render("strikethrough", null, true, 24, null)).get(0);

        assertEquals(12 * 0.27f, at12, 0.05f);
        assertEquals(24 * 0.27f, at24, 0.05f);
        assertTrue(at24 > at12 + 3f, "the offset must track font size, not stay constant");
    }

    @Test
    void bothDecorationsCanApplyToOneRun() throws Exception {
        ObjectNode run = MAPPER.createObjectNode();
        run.put("type", "text").put("text", "Sample")
                .put("underline", true).put("strikethrough", true);
        ObjectNode content = MAPPER.createObjectNode();
        content.put("rich", true);
        content.putArray("runs").add(run);

        ObjectNode el = MAPPER.createObjectNode();
        el.put("id", "x").put("type", "TEXT")
                .put("x", 40).put("y", 100).put("width", 300).put("height", 60);
        el.set("content", content);
        el.putObject("style").put("fontSize", 12).put("fontFamily", "Inter").put("color", "#111827");
        ObjectNode layout = MAPPER.createObjectNode();
        layout.putObject("page").put("size", "A4");
        layout.putArray("pages").addObject().putArray("elements").add(el);

        // iText merges repeated setUnderline calls into a list rather than
        // replacing, which is what lets one run carry both.
        assertEquals(2, decorations(renderer().render(layout, MAPPER.createObjectNode())));
    }

    // ── the link affordance must not double-stroke ─────────────────────────────

    @Test
    void aLinkInsideAnUnderlinedElementIsStrokedOnce() throws Exception {
        // The link branch adds an underline as a visual affordance. Now that a
        // run inherits the element's underline, checking only the run's own
        // value would lay a second identical stroke over the first.
        assertEquals(1, decorations(render("underline", true, null, 12, "https://crixaa.test")),
                "one underline, not two");
    }

    @Test
    void anUnstyledLinkStillGetsItsUnderline() throws Exception {
        assertEquals(1, decorations(render("underline", null, null, 12, "https://crixaa.test")),
                "a link with no styling anywhere still reads as a link");
    }
}
