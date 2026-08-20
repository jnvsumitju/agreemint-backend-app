package com.agreemint.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The PDF must format a piped variable exactly as the canvas did.
 *
 * <p>Asserted against {@code pipe-parity.json}, produced by running the
 * console's own {@code applyPipes} over a table of inputs — so the expected
 * values are not a Java author's opinion of what Intl.NumberFormat does, they
 * are what it actually did.
 *
 * <p>That distinction is the point. The failure this guards against is not a
 * crash, it is <em>nearly</em> right: a grouping separator on a number the
 * canvas leaves bare, a currency symbol on the wrong side, a half-even rounding
 * that differs from the browser in the final digit. Every one of those ships a
 * PDF that disagrees with the design it came from, and every one is invisible
 * until a customer compares two documents.
 *
 * <p>Regenerate with {@code npx vitest run src/lib/__pipeFixture.test.ts} in the
 * console after changing the TypeScript.
 */
class PipeParityTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode fixture() throws Exception {
        try (InputStream in = PipeParityTest.class.getResourceAsStream("/pipe-parity.json")) {
            if (in == null) {
                fail("pipe-parity.json missing — regenerate it from the console");
            }
            return M.readTree(in);
        }
    }

    /** The console stringifies the value before piping; mirror that here. */
    private static String asString(JsonNode v) {
        if (v == null || v.isNull()) return "";
        return v.isTextual() ? v.asText() : v.asText();
    }

    @Test
    void everyCaseMatchesTheCanvas() throws Exception {
        JsonNode rows = fixture();
        assertTrue(rows.size() >= 20, "fixture looks truncated: " + rows.size() + " cases");

        List<String> mismatches = new ArrayList<>();
        for (JsonNode row : rows) {
            String expr = row.get("expr").asText();
            String expected = row.get("expected").asText();

            VariablePipes.Parsed parsed = VariablePipes.parse(expr);
            String actual = VariablePipes.apply(asString(row.get("value")), parsed.pipes());

            if (!expected.equals(actual)) {
                mismatches.add(String.format(
                        "  %-30s value=%-14s canvas=%-14s pdf=%s",
                        expr, row.get("value"), quote(expected), quote(actual)));
            }
        }
        if (!mismatches.isEmpty()) {
            fail("PDF output diverges from the canvas in " + mismatches.size()
                    + " case(s):\n" + String.join("\n", mismatches));
        }
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    @Test
    void theKeyIsSeparatedFromItsPipes() {
        assertEquals("total", VariablePipes.parse("total | currency:\"INR\"").key());
        assertEquals("a.b.c", VariablePipes.parse("a.b.c|uppercase").key());
        assertEquals("plain", VariablePipes.parse("plain").key());
        assertTrue(VariablePipes.parse("plain").pipes().isEmpty());
    }

    @Test
    void anArgumentMayContainAColon() {
        // Splitting on every colon rather than the first would break a time format.
        VariablePipes.Parsed p = VariablePipes.parse("when | date:\"HH:mm\"");
        assertEquals(1, p.pipes().size());
        assertEquals("HH:mm", p.pipes().get(0).arg());
    }
}
