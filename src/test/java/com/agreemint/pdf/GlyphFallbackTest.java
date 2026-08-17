package com.agreemint.pdf;

import com.agreemint.config.PixelParityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.itextpdf.layout.element.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characters must be drawn by a font that actually has them.
 *
 * <p>The three shipped families do not have equal coverage: JetBrains Mono has
 * no U+20B9 (₹). iText draws a missing glyph as notdef rather than substituting,
 * so a totals column set in mono printed {@code ▯1,41,600.00} on Indian tax
 * invoices while the identical amount in Inter rendered correctly — and nothing
 * upstream raised a word about it.
 *
 * <p>The renderer now splits such a run so only the uncovered characters move to
 * Inter and the rest stay monospaced, which is what keeps a column of figures
 * aligned.
 */
class GlyphFallbackTest {

    private static PdfFontRegistry registry;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String RUPEE = "₹";

    @BeforeAll
    static void loadRegistry() {
        registry = new PdfFontRegistry();
        ReflectionTestUtils.invokeMethod(registry, "loadPrograms");
        assertTrue(registry.isFullyLoaded(), "TTFs must be present under classpath:fonts/");
    }

    private PdfRendererService parityRenderer() {
        PixelParityProperties flag = new PixelParityProperties();
        flag.setEnabled(true);
        return new PdfRendererService(
                mapper, new LayoutBehaviourResolver(mapper), registry, flag, "https://crixaa.test");
    }

    @Test
    void theGapThisGuardsIsReal() {
        // If JetBrains Mono ever ships ₹, this test's premise is gone and the
        // split below would be silently vacuous. Assert the premise directly.
        assertFalse(registry.createFont(PdfFontRegistry.FAMILY_MONO, false, false)
                        .containsGlyph(RUPEE.codePointAt(0)),
                "premise: JetBrains Mono has no rupee glyph");
        assertTrue(registry.createFont(PdfFontRegistry.FAMILY_SANS, false, false)
                        .containsGlyph(RUPEE.codePointAt(0)),
                "premise: Inter does");
    }

    @Test
    void onlyTheUncoveredCharactersMoveToTheFallback() {
        PdfFont mono = registry.createFont(PdfFontRegistry.FAMILY_MONO, false, false);
        PdfFont inter = registry.createFont(PdfFontRegistry.FAMILY_SANS, false, false);

        List<Text> runs = parityRenderer().splitForGlyphCoverage(RUPEE + "1,41,600.00", mono, inter);

        assertEquals(2, runs.size(), "one fallback run for ₹, one mono run for the digits");
        // The rupee run is pinned to Inter; the digits inherit the paragraph's mono.
        assertEquals(inter, runs.get(0).<PdfFont>getProperty(
                com.itextpdf.layout.properties.Property.FONT));
        assertEquals(null, runs.get(1).<PdfFont>getProperty(
                com.itextpdf.layout.properties.Property.FONT),
                "covered text must inherit rather than be pinned, so it stays monospaced");

        StringBuilder rebuilt = new StringBuilder();
        for (Text t : runs) rebuilt.append(t.getText());
        assertEquals(RUPEE + "1,41,600.00", rebuilt.toString(), "no character may be lost or duplicated");
    }

    @Test
    void fullyCoveredTextIsNotSplitAtAll() {
        PdfFont inter = registry.createFont(PdfFontRegistry.FAMILY_SANS, false, false);
        PdfFont mono = registry.createFont(PdfFontRegistry.FAMILY_MONO, false, false);
        // Inter covers ₹, so the common case must allocate a single unpinned run.
        List<Text> runs = parityRenderer().splitForGlyphCoverage(RUPEE + "500", inter, mono);
        assertEquals(1, runs.size());
        assertEquals(null, runs.get(0).<PdfFont>getProperty(
                com.itextpdf.layout.properties.Property.FONT));
    }

    @Test
    void multipleRupeesInOneStringAllSurvive() {
        PdfFont mono = registry.createFont(PdfFontRegistry.FAMILY_MONO, false, false);
        PdfFont inter = registry.createFont(PdfFontRegistry.FAMILY_SANS, false, false);
        String s = RUPEE + "10 to " + RUPEE + "20";
        StringBuilder rebuilt = new StringBuilder();
        for (Text t : parityRenderer().splitForGlyphCoverage(s, mono, inter)) rebuilt.append(t.getText());
        assertEquals(s, rebuilt.toString());
    }

    @Test
    void aRenderedMonoAmountKeepsItsRupeeSign() throws Exception {
        // End to end: the character has to survive into the PDF's text content,
        // which is what the unit-level split exists to achieve.
        ObjectNode layout = mapper.createObjectNode();
        ObjectNode page = layout.putObject("page");
        page.put("size", "A4");
        page.put("margin", 40);
        ObjectNode el = layout.putArray("elements").addObject();
        el.put("id", "t1");
        el.put("type", "TEXT");
        el.put("x", 40);
        el.put("y", 40);
        el.put("width", 400);
        el.put("height", 40);
        el.put("content", RUPEE + "1,41,600.00");
        ObjectNode style = el.putObject("style");
        style.put("fontSize", 11);
        style.put("fontFamily", PdfFontRegistry.FAMILY_MONO);
        style.put("lineHeight", 1.45);

        byte[] pdf = parityRenderer().render(layout, JsonNodeFactory.instance.objectNode());
        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            String extracted = PdfTextExtractor.getTextFromPage(doc.getPage(1));
            assertTrue(extracted.contains(RUPEE),
                    "the rupee sign must reach the PDF, got: " + extracted);
            assertTrue(extracted.contains("1,41,600.00"), "and the amount with it: " + extracted);
        }
    }
}
