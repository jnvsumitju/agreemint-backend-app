package com.agreemint.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape of `data` an API caller has to send, and why the natural guess fails.
 *
 * <p>451 distinct variable names across the shipped templates contain a dot —
 * {@code company.name}, {@code employment.designation}. The console shows the
 * placeholder exactly like that, so a developer writing an integration puts
 * {@code "company.name"} in their JSON. The renderer splits on the dot and
 * walks, finds no {@code company} object, and prints nothing: a structurally
 * valid PDF with every merged field blank, no exception, no log line.
 *
 * <p>These tests pin the conversion that makes both shapes work. They deal in
 * the data tree rather than PDF bytes because the bug is entirely in path
 * resolution, and asserting on rendered glyphs would test iText instead.
 */
class ApiDataShapeTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Mirrors PdfRendererService.resolveDataPath — split on '.', walk, null if absent. */
    private static JsonNode resolve(JsonNode root, String path) {
        JsonNode cur = root;
        for (String part : path.split("\\.")) {
            if (cur == null || !cur.has(part)) return null;
            cur = cur.get(part);
        }
        return cur;
    }

    @Test
    void reproducesTheBug_flatDottedKeysResolveToNothing() {
        // Exactly what a developer sends after reading {{company.name}} in the UI.
        JsonNode asSent = json("{\"company.name\":\"Acme Ltd\",\"employment.designation\":\"Engineer\"}");

        assertNull(resolve(asSent, "company.name"),
                "this is the bug: the value is present in the payload and unreachable by the renderer");
        assertNull(resolve(asSent, "employment.designation"));
    }

    @Test
    void theConversionMakesTheSamePayloadWork() {
        JsonNode asSent = json("{\"company.name\":\"Acme Ltd\",\"employment.designation\":\"Engineer\"}");

        JsonNode converted = VariableDataTree.build(null, asSent);

        assertEquals("Acme Ltd", resolve(converted, "company.name").asText());
        assertEquals("Engineer", resolve(converted, "employment.designation").asText());
    }

    @Test
    void alreadyNestedPayloadsAreLeftWorking() {
        // The documented example shape must not regress.
        JsonNode nested = json("{\"company\":{\"name\":\"Acme Ltd\"},\"customer\":\"Beta\",\"amount\":2400}");

        JsonNode converted = VariableDataTree.build(null, nested);

        assertEquals("Acme Ltd", resolve(converted, "company.name").asText());
        assertEquals("Beta", resolve(converted, "customer").asText());
        assertEquals(2400, resolve(converted, "amount").asInt());
    }

    @Test
    void aMixOfBothMergesRatherThanClobbering() {
        // The likeliest real payload: someone nests what is obvious and leaves
        // the rest dotted. Naive expansion would drop one side.
        JsonNode mixed = json("{\"company\":{\"name\":\"Acme\"},\"company.city\":\"Pune\"}");

        JsonNode converted = VariableDataTree.build(null, mixed);

        assertEquals("Acme", resolve(converted, "company.name").asText(), "nested sibling was lost");
        assertEquals("Pune", resolve(converted, "company.city").asText(), "dotted sibling was lost");
    }

    @Test
    void systemKeysAreStrippedSoTheRendererComputesThem() {
        // currentDate/pageNumber/totalPages are the renderer's to fill. A caller
        // passing them would otherwise stamp their literal onto the page.
        JsonNode withSystem = json("{\"currentDate\":\"Currentdate\",\"customer\":\"Acme\"}");

        JsonNode converted = VariableDataTree.build(null, withSystem);

        assertNull(resolve(converted, "currentDate"));
        assertNotNull(resolve(converted, "customer"));
    }

    @Test
    void deeperThanTwoLevelsAlsoWorks() {
        JsonNode deep = json("{\"a.b.c.d\":\"x\"}");
        assertEquals("x", resolve(VariableDataTree.build(null, deep), "a.b.c.d").asText());
    }

    /**
     * The layout-aware form, which is what the generate path now calls.
     *
     * <p>Passing the layout matters for TABLE elements: rows arrive as a JSON
     * string of objects and have to be projected onto the columns the layout
     * declares before the blank-row filter runs. The layout-free overload
     * cannot do that, which is why the deprecated one exists only for callers
     * that have no layout to hand.
     */
    @Test
    void theLayoutAwareFormBehavesTheSameForScalars() {
        JsonNode layout = json("{\"elements\":[],\"pages\":[{\"elements\":[]}]}");
        JsonNode asSent = json("{\"company.name\":\"Acme Ltd\"}");

        assertEquals("Acme Ltd", resolve(VariableDataTree.build(layout, asSent), "company.name").asText());
    }

    @Test
    void nullAndNonObjectDataDoNotThrow() {
        assertTrue(VariableDataTree.build(null, null).isEmpty());
        assertTrue(VariableDataTree.build(null, json("[]")).isEmpty());
        assertTrue(VariableDataTree.build(null, json("\"nope\"")).isEmpty());
    }
}
