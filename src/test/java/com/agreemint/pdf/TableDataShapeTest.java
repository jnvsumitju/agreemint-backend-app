package com.agreemint.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How an API caller supplies rows for a data-bound table.
 *
 * <p>The console stores table rows as a JSON <em>string</em>, because its
 * variables panel is a flat key-to-string map. That storage detail leaked into
 * the API examples, so an integrator ends up writing
 * {@code "transactions": "[{\\"date\\":\\"03 Jul\\",…}]"} — stringifying an
 * array inside a JSON body, escaping every quote by hand, and doing it again
 * one level deeper for a rich-text cell.
 *
 * <p>These tests pin that a plain array works identically, so the ugly form is
 * a compatibility path for what the console writes rather than the shape anyone
 * has to author.
 */
class TableDataShapeTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A layout with one data-bound table over `transactions`. */
    private static final JsonNode LAYOUT = json("""
            {"page":{"size":"A4"},"elements":[{
              "id":"t1","type":"TABLE","dataKey":"transactions","tableLoop":true,
              "columns":[{"key":"date","header":"Date"},
                         {"key":"reference","header":"Ref"},
                         {"key":"debit","header":"Debit"}]}]}
            """);

    @Test
    void aPlainArrayWorks() {
        JsonNode data = json("""
                {"transactions":[
                  {"date":"03 Jul 2026","reference":"INV-2201","debit":"45000.00"},
                  {"date":"08 Jul 2026","reference":"PMT-0091","debit":"-"}]}
                """);

        JsonNode rows = VariableDataTree.build(LAYOUT, data).get("transactions");

        assertTrue(rows.isArray(), "a native array should be accepted as-is");
        assertEquals(2, rows.size());
        assertEquals("INV-2201", rows.get(0).get("reference").asText());
    }

    @Test
    void theStringifiedFormStillWorks() {
        // What the console stores, and what the old examples showed. It has to
        // keep working or every template authored in the editor breaks.
        JsonNode data = json("""
                {"transactions":"[{\\"date\\":\\"03 Jul 2026\\",\\"reference\\":\\"INV-2201\\",\\"debit\\":\\"45000.00\\"}]"}
                """);

        JsonNode rows = VariableDataTree.build(LAYOUT, data).get("transactions");

        assertTrue(rows.isArray());
        assertEquals("INV-2201", rows.get(0).get("reference").asText());
    }

    @Test
    void bothFormsProduceIdenticalRows() {
        JsonNode asArray = VariableDataTree.build(LAYOUT, json("""
                {"transactions":[{"date":"03 Jul","reference":"INV-1","debit":"10"}]}"""));
        JsonNode asString = VariableDataTree.build(LAYOUT, json("""
                {"transactions":"[{\\"date\\":\\"03 Jul\\",\\"reference\\":\\"INV-1\\",\\"debit\\":\\"10\\"}]"}"""));

        assertEquals(asArray, asString, "the two spellings must render the same document");
    }

    @Test
    void numbersDoNotHaveToBeStrings() {
        // Nothing forces a caller to pre-format. Cells are stringified during
        // column projection.
        JsonNode rows = VariableDataTree.build(LAYOUT, json("""
                {"transactions":[{"date":"03 Jul","reference":"INV-1","debit":45000}]}""")).get("transactions");

        assertEquals("45000", rows.get(0).get("debit").asText());
    }

    @Test
    void columnsTheTableDoesNotDrawAreDropped() {
        JsonNode rows = VariableDataTree.build(LAYOUT, json("""
                {"transactions":[{"date":"03 Jul","reference":"INV-1","debit":"10","internal_id":"xyz"}]}"""))
                .get("transactions");

        assertEquals(3, rows.get(0).size(), "projection should keep only declared columns");
        assertTrue(rows.get(0).get("internal_id") == null);
    }

    /**
     * The shape the console ACTUALLY writes when you switch a table to Loop.
     *
     * <p>Not an array of row objects — {@code PropertiesPanel.handleLoopToggle}
     * calls {@code serializeTableVariableData}, which emits
     * {@code {"data":[[headers],[row]],"cellStyle":…,"borderStyle":…}}. The
     * earlier tests in this class covered a stringified array and a native
     * array, neither of which a customer's table produces.
     */
    @Test
    void theShapeTheConsoleActuallyWritesIsHandled() {
        String structured = "{\"data\":[[\"date\",\"reference\",\"debit\"],"
                + "[\"03 Jul 2026\",\"INV-2201\",\"45000.00\"],"
                + "[\"08 Jul 2026\",\"PMT-0091\",\"-\"]],"
                + "\"cellStyle\":{\"fontSize\":9},\"borderStyle\":{\"width\":0.5}}";
        JsonNode data = M.createObjectNode().put("transactions", structured);

        JsonNode rows = VariableDataTree.build(LAYOUT, data).get("transactions");

        assertEquals(2, rows.size(),
                "a table built in the console renders with no rows at all: " + rows);
        assertEquals("INV-2201", rows.get(0).get("reference").asText());
    }

    @Test
    void structuredCellsMapByPositionNotByHeaderName() {
        // Headers deliberately disagree with the layout's column keys. The
        // console addresses body cells by position, so the projection must too
        // — matching on the header text would silently blank every cell in a
        // table whose columns were renamed after the data was entered.
        String structured = "{\"data\":[[\"Col A\",\"Col B\",\"Col C\"],"
                + "[\"03 Jul 2026\",\"INV-9\",\"999\"]]}";
        JsonNode rows = VariableDataTree.build(
                LAYOUT, M.createObjectNode().put("transactions", structured)).get("transactions");

        assertEquals("03 Jul 2026", rows.get(0).get("date").asText());
        assertEquals("INV-9", rows.get(0).get("reference").asText());
        assertEquals("999", rows.get(0).get("debit").asText());
    }

    @Test
    void aHeaderOnlyStructuredTableYieldsNoRows() {
        // data[0] is the header row; a grid with nothing after it is an empty
        // table, not one blank row.
        JsonNode rows = VariableDataTree.build(LAYOUT, M.createObjectNode()
                .put("transactions", "{\"data\":[[\"date\",\"reference\",\"debit\"]]}"))
                .get("transactions");
        assertEquals(0, rows.size());
    }

    @Test
    void aBlankStructuredRowIsFilteredLikeALegacyOne() {
        // The table editor keeps a blank row while the author is typing, so one
        // legitimately reaches storage and must not print as an empty line.
        JsonNode rows = VariableDataTree.build(LAYOUT, M.createObjectNode()
                .put("transactions", "{\"data\":[[\"date\",\"reference\",\"debit\"],"
                        + "[\"03 Jul\",\"INV-1\",\"10\"],[\"\",\"\",\"\"]]}"))
                .get("transactions");
        assertEquals(1, rows.size());
    }

    @Test
    void aShortStructuredRowPadsRatherThanThrowing() {
        // A row with fewer cells than the table has columns — reachable after
        // adding a column to a table that already had data.
        JsonNode rows = VariableDataTree.build(LAYOUT, M.createObjectNode()
                .put("transactions", "{\"data\":[[\"date\",\"reference\",\"debit\"],"
                        + "[\"03 Jul\",\"INV-1\"]]}"))
                .get("transactions");
        assertEquals(1, rows.size());
        assertEquals("", rows.get(0).get("debit").asText());
    }

    @Test
    void styleKeysNeverLeakIntoTheRows() {
        // cellStyle and borderStyle live beside `data` and are the canvas's
        // business. They must not become columns.
        JsonNode rows = VariableDataTree.build(LAYOUT, M.createObjectNode()
                .put("transactions", "{\"data\":[[\"date\",\"reference\",\"debit\"],"
                        + "[\"03 Jul\",\"INV-1\",\"10\"]],"
                        + "\"cellStyle\":{\"fontSize\":9},\"borderStyle\":{\"width\":0.5}}"))
                .get("transactions");
        assertEquals(3, rows.get(0).size());
        assertTrue(rows.get(0).get("cellStyle") == null);
    }

    @Test
    void anObjectThatIsNotAStructuredTableStillYieldsNothing() {
        // Guards the new branch from swallowing unrelated objects.
        JsonNode rows = VariableDataTree.build(LAYOUT, M.createObjectNode()
                .put("transactions", "{\"unrelated\":true}")).get("transactions");
        assertEquals(0, rows.size());
    }

    @Test
    void aTableInsideAHeaderBandIsProjectedToo() {
        // The walker used to iterate pages[].elements only. A TABLE nested in a
        // header or footer band was invisible to it, so its rows were never
        // projected and its blank rows printed as empty lines.
        JsonNode layout = json("""
                {"page":{"size":"A4"},"pages":[{"elements":[
                  {"id":"h1","type":"HEADER","bandElements":[
                    {"id":"t1","type":"TABLE","dataKey":"lines","tableLoop":true,
                     "columns":[{"key":"sku","header":"SKU"}]}]}]}]}
                """);

        JsonNode rows = VariableDataTree.build(layout, json("""
                {"lines":[{"sku":"A-1","dropme":"x"},{"nothing":"here"}]}""")).get("lines");

        assertEquals(1, rows.size(), "blank row not filtered inside a band");
        assertEquals(1, rows.get(0).size(), "undeclared column kept inside a band");
    }

    @Test
    void aRowWithNothingInAnyDrawnColumnIsSkipped() {
        JsonNode rows = VariableDataTree.build(LAYOUT, json("""
                {"transactions":[{"date":"03 Jul","reference":"INV-1","debit":"10"},
                                 {"note":"only in an undrawn column"}]}""")).get("transactions");

        assertEquals(1, rows.size(), "a row with no drawn content must not print as an empty line");
    }
}
