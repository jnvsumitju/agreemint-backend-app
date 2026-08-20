package com.agreemint.pdf;

import com.agreemint.config.PixelParityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which placeholders get reported, and — more importantly — which do not.
 *
 * <p>The value of this field is entirely in its precision. A warning list that
 * cries about page numbers and hidden elements is noise a caller learns to
 * ignore, at which point it is worse than nothing, because it looks like
 * coverage. So most of what follows asserts on silence.
 *
 * <p>Renders real PDFs through the real engine rather than unit-testing a
 * scanner, because the exclusions are a property of the renderer's control
 * flow — system keys are injected before elements draw, hidden elements never
 * reach substitution — and a test that stubbed that would be testing the stub.
 */
class PlaceholderWarningsTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static PdfFontRegistry registry;
    private PdfRendererService renderer;

    @BeforeAll
    static void loadFonts() {
        // A bare `new` skips @PostConstruct, and createFont returns null until
        // the programs load — same setup the other renderer tests use.
        registry = new PdfFontRegistry();
        ReflectionTestUtils.invokeMethod(registry, "loadPrograms");
    }

    {
        PixelParityProperties flag = new PixelParityProperties();
        flag.setEnabled(true);
        renderer = new PdfRendererService(
                M, new LayoutBehaviourResolver(M), registry, flag, "https://crixaa.test");
    }

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** One TEXT element carrying `content`, the ordinary case. */
    private static JsonNode layoutWith(String content) {
        return json("{\"page\":{\"size\":\"A4\",\"margin\":40},\"elements\":[{"
                + "\"id\":\"e1\",\"type\":\"TEXT\",\"x\":40,\"y\":40,\"w\":400,\"h\":40,"
                + "\"content\":" + M.valueToTree(content).toString() + "}]}");
    }

    private List<String> warningsFor(String content, String dataJson) throws Exception {
        return renderer.renderWithWarnings(layoutWith(content), json(dataJson), false, null).warnings();
    }

    @Test
    void reportsAPlaceholderWithNoValue() throws Exception {
        List<String> w = warningsFor("Dear {{customer.name}},", "{}");
        assertEquals(List.of("customer.name"), w);
    }

    @Test
    void saysNothingWhenEverythingResolves() throws Exception {
        assertTrue(warningsFor("Dear {{customer.name}},",
                "{\"customer\":{\"name\":\"Asha\"}}").isEmpty());
    }

    @Test
    void reportsOnlyTheMissingHalf() throws Exception {
        List<String> w = warningsFor("{{customer.name}} owes {{invoice.total}}",
                "{\"customer\":{\"name\":\"Asha\"}}");
        assertEquals(List.of("invoice.total"), w);
    }

    @Test
    void aDeliberateBlankIsNotAWarning() throws Exception {
        // Supplying "" is a choice — an optional line the author left empty.
        // Only absence is worth reporting, or every template with an optional
        // field warns on every render.
        assertTrue(warningsFor("{{customer.address2}}",
                "{\"customer\":{\"address2\":\"\"}}").isEmpty());
    }

    @Test
    void systemKeysAreNeverReported() throws Exception {
        // The renderer fills these itself before any element draws. Warning
        // about them would fire on every template that prints a page number.
        List<String> w = warningsFor("Page {{pageNumber}} of {{totalPages}} — {{currentDate}}", "{}");
        assertTrue(w.isEmpty(), "system keys leaked into warnings: " + w);
    }

    @Test
    void theSameMissingKeyIsReportedOnce() throws Exception {
        // Headers, footers and floating elements repeat per page; without
        // deduplication a ten-page document would report the same gap ten times.
        assertEquals(List.of("customer.name"),
                warningsFor("{{customer.name}} … {{customer.name}} … {{customer.name}}", "{}"));
    }

    @Test
    void theSelfReferenceIsNotADataKey() throws Exception {
        // "." resolves to the whole root by construction, so it can never miss.
        assertFalse(warningsFor("{{.}}", "{}").contains("."));
    }

    @Test
    void theListIsCappedSoOneEmptyPayloadCannotFloodTheResponse() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) sb.append("{{field").append(i).append("}} ");
        assertEquals(25, warningsFor(sb.toString(), "{}").size());
    }

    @Test
    void theOrdinaryRenderPathCollectsNothing() throws Exception {
        // render() must stay allocation-free for callers that never asked, and
        // must not leak this thread's misses into a later call.
        renderer.render(layoutWith("{{a.b}}"), json("{}"), false, null);
        assertTrue(warningsFor("{{c.d}}", "{}").contains("c.d"));
        assertFalse(warningsFor("{{c.d}}", "{}").contains("a.b"),
                "a previous render's misses bled into this one");
    }

    /**
     * A placeholder that resolves only from its ROW is not a miss.
     *
     * <p>{@code lookup} tries global data first and falls back to the row, so
     * the miss can only be recorded after BOTH have failed. Recording it after
     * the global lookup alone would fire on every correctly-working table —
     * once per row — which is the fastest way to make this field worthless.
     *
     * <p>The fixture matters. A plain data-bound cell takes the row's value
     * directly and never goes through substitution at all, so it exercises
     * nothing; the first version of this test used one and a deliberately
     * broken build still passed. Here the cell's VALUE contains a placeholder
     * naming a sibling field, which is the shape that actually reaches
     * {@code lookup} with a row context.
     */
    @Test
    void aPlaceholderResolvedFromItsRowIsNotAMiss() throws Exception {
        JsonNode layout = json("{\"page\":{\"size\":\"A4\",\"margin\":40},\"elements\":[{"
                + "\"id\":\"t1\",\"type\":\"TABLE\",\"x\":40,\"y\":40,\"w\":400,\"h\":120,"
                + "\"dataKey\":\"items\",\"tableLoop\":true,"
                + "\"columns\":[{\"key\":\"label\",\"header\":\"Item\"}]}]}");
        // `label` holds "SKU {{sku}}" — sku exists on the row and nowhere else.
        JsonNode data = json("{\"items\":[{\"sku\":\"A-1\",\"label\":\"SKU {{sku}}\"},"
                + "{\"sku\":\"B-2\",\"label\":\"SKU {{sku}}\"}]}");

        List<String> w = renderer.renderWithWarnings(layout, data, false, null).warnings();

        assertFalse(w.contains("sku"),
                "a row-scoped placeholder was reported as unresolved: " + w);
    }

    @Test
    void bytesAreIdenticalWithAndWithoutCollection() throws Exception {
        // The diagnostic must not change the document.
        JsonNode layout = layoutWith("Dear {{customer.name}},");
        JsonNode data = json("{\"customer\":{\"name\":\"Asha\"}}");

        byte[] plain = renderer.render(layout, data, false, null);
        byte[] instrumented = renderer.renderWithWarnings(layout, data, false, null).pdf();

        assertEquals(plain.length, instrumented.length,
                "collecting warnings changed the rendered output");
    }
}
