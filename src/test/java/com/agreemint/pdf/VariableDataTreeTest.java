package com.agreemint.pdf;

import com.agreemint.config.PixelParityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape conversion between what the editor saves and what the renderer reads.
 *
 * <p>These are two different shapes and the mismatch is silent in both
 * directions. The editor's Variables panel is a flat form, so a key is the
 * literal string {@code "totals.grand_total"} and a table's rows are a JSON
 * string; that is what is stored, because it is what has to be loaded back into
 * the form. The renderer splits placeholders on {@code .} and walks nested
 * objects, and requires a real array for a data-bound table.
 *
 * <p>Feed it the stored shape and nothing throws, nothing is logged, and the
 * PDF comes out valid and blank. That is why the last test here renders a real
 * shipped template and reads the text back rather than asserting on the tree:
 * a correct-looking tree that still renders empty would pass every other test
 * in this file.
 */
class VariableDataTreeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static PdfFontRegistry registry;

    @BeforeAll
    static void loadFonts() {
        registry = new PdfFontRegistry();
        ReflectionTestUtils.invokeMethod(registry, "loadPrograms");
    }

    /** A layout with one TABLE bound to {@code dataKey}, declaring {@code cols}. */
    private static ObjectNode layoutWithTable(String dataKey, String... cols) {
        ObjectNode table = MAPPER.createObjectNode();
        table.put("type", "TABLE").put("dataKey", dataKey);
        var columns = table.putArray("columns");
        for (String c : cols) columns.addObject().put("key", c);

        ObjectNode layout = MAPPER.createObjectNode();
        layout.putArray("pages").addObject().putArray("elements").add(table);
        return layout;
    }

    private static ObjectNode flat(String... kv) {
        ObjectNode n = MAPPER.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) n.put(kv[i], kv[i + 1]);
        return n;
    }

    // ── dotted keys ───────────────────────────────────────────────────────────

    @Test
    void aDottedKeyBecomesNestedObjects() {
        JsonNode t = VariableDataTree.build(flat("totals.grand_total", "₹1,42,360.00"));

        assertEquals("₹1,42,360.00", t.path("totals").path("grand_total").asText());
        // And specifically NOT still present under the literal dotted name,
        // which is what the renderer cannot read.
        assertFalse(t.has("totals.grand_total"));
    }

    @Test
    void siblingsUnderTheSamePrefixShareOneParent() {
        JsonNode t = VariableDataTree.build(
                flat("company.name", "Northwind", "company.gstin", "29ABCDE1234F1Z5"));

        assertEquals("Northwind", t.path("company").path("name").asText());
        assertEquals("29ABCDE1234F1Z5", t.path("company").path("gstin").asText());
        assertEquals(2, t.path("company").size());
    }

    @Test
    void threeLevelsDeepStillResolves() {
        JsonNode t = VariableDataTree.build(flat("a.b.c", "deep"));
        assertEquals("deep", t.path("a").path("b").path("c").asText());
    }

    @Test
    void anUndottedKeyIsLeftWhereItIs() {
        JsonNode t = VariableDataTree.build(flat("subject", "Offer of employment"));
        assertEquals("Offer of employment", t.path("subject").asText());
    }

    @Test
    void aScalarBlockingAPathIsReplacedByTheObject() {
        // "a" and "a.b" cannot both survive — the renderer can only walk into an
        // object. Matches the console, which does the same thing.
        JsonNode t = VariableDataTree.build(flat("a", "scalar", "a.b", "nested"));
        assertEquals("nested", t.path("a").path("b").asText());
    }

    @Test
    void aTrailingDotKeepsItsEmptySegment() {
        // Java's String.split drops trailing empty strings and JavaScript's does
        // not, so without an explicit limit this would nest one level shallower
        // here than the editor did and the two would disagree.
        JsonNode t = VariableDataTree.build(flat("a.", "x"));
        assertEquals("x", t.path("a").path("").asText());
    }

    // ── JSON-in-a-string ──────────────────────────────────────────────────────

    @Test
    void aStringifiedTableBecomesARealArray() {
        JsonNode t = VariableDataTree.build(flat(
                "line_items", "[{\"description\":\"Steel shelving\",\"qty\":\"10\"}]"));

        assertTrue(t.path("line_items").isArray(), "a table in loop mode draws nothing unless this is an array");
        assertEquals(1, t.path("line_items").size());
        assertEquals("Steel shelving", t.path("line_items").get(0).path("description").asText());
    }

    @Test
    void anEmptyTableStaysAnEmptyArrayRatherThanAString() {
        // The editor seeds untouched table keys with the literal text "[]".
        JsonNode t = VariableDataTree.build(flat("line_items", "[]"));
        assertTrue(t.path("line_items").isArray());
        assertEquals(0, t.path("line_items").size());
    }

    @Test
    void blankRowsAreDroppedBecauseTheEditorKeepsThemOnPurpose() {
        // The table editor holds a blank row while the author is filling it in —
        // removing it under them was a real bug — so a blank row legitimately
        // reaches storage and must not print as an empty line.
        JsonNode t = VariableDataTree.build(
                layoutWithTable("rows", "a"),
                flat("rows", "[{\"a\":\"real\"},{\"a\":\"\"},{\"a\":\"   \"}]"));
        assertEquals(1, t.path("rows").size());
    }

    @Test
    void aPartlyFilledRowIsKept() {
        JsonNode t = VariableDataTree.build(
                layoutWithTable("rows", "a", "b"),
                flat("rows", "[{\"a\":\"name\",\"b\":\"\"}]"));
        assertEquals(1, t.path("rows").size());
    }

    @Test
    void rowsAreReducedToTheColumnsTheTableActuallyDraws() {
        // The console filters rows AFTER projecting them onto the column keys,
        // so content under a key the table does not render does not keep a row
        // alive. Filtering the raw rows instead would draw a line of empty cells
        // — which is what an author sees after renaming a column.
        JsonNode t = VariableDataTree.build(
                layoutWithTable("rows", "description", "qty"),
                flat("rows", "[{\"item\":\"Steel shelving\",\"amt\":\"100\"}]"));

        assertEquals(0, t.path("rows").size(),
                "a row with nothing under any declared column is blank, not a row of empty cells");
    }

    @Test
    void aCellIsKeyedByColumnEvenWhenTheStoredRowHasExtras() {
        JsonNode t = VariableDataTree.build(
                layoutWithTable("rows", "description"),
                flat("rows", "[{\"description\":\"Steel\",\"stale\":\"x\"}]"));

        assertEquals(1, t.path("rows").size());
        assertEquals("Steel", t.path("rows").get(0).path("description").asText());
        assertFalse(t.path("rows").get(0).has("stale"));
    }

    @Test
    void aListIsNotBlankFiltered() {
        // Only TABLE dataKeys get the blank-row rule; the console does not touch
        // lists. Dropping a deliberately blank item would renumber every item
        // after it in an ordered list.
        JsonNode t = VariableDataTree.build(
                layoutWithTable("rows", "a"),
                flat("items", "[{\"text\":\"Scope\"},{\"text\":\"\"},{\"text\":\"Fees\"}]"));

        assertEquals(3, t.path("items").size());
    }

    @Test
    void aValueThatMerelyStartsWithABracketIsLeftAlone() {
        JsonNode t = VariableDataTree.build(flat("note", "[see appendix"));
        assertEquals("[see appendix", t.path("note").asText());
    }

    @Test
    void trailingTokensAreRejectedTheWayJsonParseRejectsThem() {
        // Jackson stops at the first complete value by default and would read
        // this as a one-row table. JSON.parse throws, so the console shows the
        // raw text — meaning the thumbnail would look MORE correct than the
        // preview it is supposed to be a picture of.
        String stray = "[{\"description\":\"x\"}] oops]";
        JsonNode t = VariableDataTree.build(flat("note", stray));
        assertEquals(stray, t.path("note").asText());
    }

    @Test
    void reservedSystemKeysAreDroppedSoTheRendererStampsItsOwn() {
        // The editor seeds every placeholder it finds with a humanised default,
        // so a layout using {{currentDate}} has the literal "Currentdate" in its
        // saved variables. The renderer only stamps the real date when the key
        // is ABSENT, so passing it through prints the word "Currentdate" where
        // the preview prints today's date — and the Variables tab hides system
        // keys, so the author cannot even see the value to correct it.
        JsonNode t = VariableDataTree.build(flat(
                "currentDate", "Currentdate",
                "pageNumber", "Pagenumber",
                "totalPages", "Totalpages",
                "invoice.number", "INV-2026-0184"));

        assertFalse(t.has("currentDate"));
        assertFalse(t.has("pageNumber"));
        assertFalse(t.has("totalPages"));
        assertEquals("INV-2026-0184", t.path("invoice").path("number").asText());
    }

    @Test
    void malformedJsonKeepsWhatTheAuthorTyped() {
        // Half-typed input must print as typed rather than vanishing.
        String halfTyped = "[{\"description\":";
        JsonNode t = VariableDataTree.build(flat("rows", halfTyped));
        assertEquals(halfTyped, t.path("rows").asText());
    }

    // ── tolerance ─────────────────────────────────────────────────────────────

    @Test
    void nullAndNonObjectInputsProduceAnEmptyObject() {
        // The renderer discards a non-object `data` anyway; returning an empty
        // object keeps the null-handling in one place.
        assertEquals(0, VariableDataTree.build(null).size());
        assertEquals(0, VariableDataTree.build(MAPPER.createArrayNode()).size());
        assertEquals(0, VariableDataTree.build(MAPPER.getNodeFactory().textNode("x")).size());
    }

    @Test
    void anAlreadyNestedTreePassesThroughUnchanged() {
        // Applied at a single choke point, so it must be idempotent — the same
        // data must not be mangled by being converted twice.
        ObjectNode already = MAPPER.createObjectNode();
        already.putObject("company").put("name", "Northwind");
        already.putArray("rows").addObject().put("a", "1");

        JsonNode once = VariableDataTree.build(null, already);
        JsonNode twice = VariableDataTree.build(null, once);

        assertEquals(once, twice);
        assertEquals("Northwind", twice.path("company").path("name").asText());
        assertTrue(twice.path("rows").isArray());
    }

    // ── the one that proves it matters ────────────────────────────────────────

    @Test
    void aRealTemplateRendersItsValuesOnlyAfterTheConversion() throws Exception {
        Path bundle = Path.of("..", "agreemint-frontend-app", "src", "try-templates",
                "free-gst-invoice-template.json");
        JsonNode payload = MAPPER.readTree(Files.readString(bundle));
        JsonNode layout = payload.path("layout");
        JsonNode storedVariables = payload.path("variableValues");

        // Sanity-check the premise rather than trusting it: this fixture is only
        // meaningful if the shipped bundle really does store dotted keys flat.
        assertTrue(storedVariables.has("company.name"),
                "fixture assumes the stored shape is flat with dotted keys");

        String company = storedVariables.path("company.name").asText();
        assertFalse(company.isBlank());

        PixelParityProperties flag = new PixelParityProperties();
        flag.setEnabled(true);
        PdfRendererService renderer = new PdfRendererService(
                MAPPER, new LayoutBehaviourResolver(MAPPER), registry, flag, "https://crixaa.test");

        String before = textOf(renderer.render(layout, storedVariables));
        String after = textOf(renderer.render(layout, VariableDataTree.build(layout, storedVariables)));

        // This is the bug, stated as an assertion: handing the renderer what is
        // actually stored produces a valid PDF with the content missing.
        assertFalse(before.contains(company),
                "the raw stored shape should render blank — if this fails the "
                        + "renderer learned to read flat dotted keys and this class is obsolete");
        assertTrue(after.contains(company),
                "after conversion the company name must actually appear on the page");
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
