package com.agreemint.pdf;

import com.agreemint.config.PixelParityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A piped placeholder must reach the page formatted, not literal.
 *
 * <p>{@link PipeParityTest} proves the formatter agrees with the canvas;
 * this proves the renderer actually reaches it. Before this, the placeholder
 * pattern did not match a pipe at all, so {@code {{total | currency}}} was not
 * a variable as far as the engine was concerned — it printed verbatim in plain
 * text, and a rich var chip resolved the whole expression as a key and came out
 * blank. Both failures are asserted against below, because both were real and
 * neither raised anything.
 */
class PipedPlaceholderRenderTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static PdfFontRegistry registry;

    @BeforeAll
    static void loadFonts() {
        registry = new PdfFontRegistry();
        ReflectionTestUtils.invokeMethod(registry, "loadPrograms");
    }

    private PdfRendererService renderer() {
        PixelParityProperties flag = new PixelParityProperties();
        flag.setEnabled(true);
        return new PdfRendererService(
                M, new LayoutBehaviourResolver(M), registry, flag, "https://crixaa.test");
    }

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String textOf(byte[] pdf) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                sb.append(PdfTextExtractor.getTextFromPage(doc.getPage(i))).append('\n');
            }
        }
        return sb.toString();
    }

    private String render(String content, String dataJson) throws Exception {
        JsonNode layout = json("{\"page\":{\"size\":\"A4\",\"margin\":40},\"elements\":[{"
                + "\"id\":\"e1\",\"type\":\"TEXT\",\"x\":40,\"y\":40,\"w\":450,\"h\":60,"
                + "\"content\":" + M.valueToTree(content) + "}]}");
        return textOf(renderer().render(layout, json(dataJson), false, null));
    }

    @Test
    void currencyIsFormattedOnThePage() throws Exception {
        String text = render("Total: {{invoice.total | currency:\"INR\"}}",
                "{\"invoice\":{\"total\":2400}}");

        assertTrue(text.contains("₹2,400.00"), "expected a formatted amount, got: " + text.trim());
    }

    @Test
    void theRawExpressionNeverReachesThePage() throws Exception {
        // The old failure: unmatched by the pattern, so printed verbatim.
        String text = render("Total: {{invoice.total | currency}}", "{\"invoice\":{\"total\":10}}");

        assertFalse(text.contains("{{"), "the placeholder printed literally: " + text.trim());
        assertFalse(text.contains("currency"), "the pipe name leaked onto the page: " + text.trim());
    }

    @Test
    void chainedPipesApplyInOrder() throws Exception {
        String text = render("{{name | uppercase | truncate:5}}", "{\"name\":\"alexandra\"}");
        assertTrue(text.contains("ALEXA"), text.trim());
    }

    @Test
    void aDefaultPipeFillsAnAbsentValue() throws Exception {
        String text = render("Ref: {{invoice.ref | default:\"not issued\"}}", "{}");
        assertTrue(text.contains("not issued"), text.trim());
    }

    @Test
    void anUnpipedPlaceholderIsUnaffected() throws Exception {
        // The regression risk of widening the pattern: ordinary placeholders
        // must behave exactly as before.
        String text = render("Dear {{customer.name}},", "{\"customer\":{\"name\":\"Asha\"}}");
        assertTrue(text.contains("Asha"), text.trim());
        assertFalse(text.contains("{{"), text.trim());
    }

    @Test
    void aDefaultedMissWarnsNobody() throws Exception {
        // An author who wrote `default:` has handled absence explicitly, so
        // reporting it as unresolved would be noise on every render.
        JsonNode layout = json("{\"page\":{\"size\":\"A4\",\"margin\":40},\"elements\":[{"
                + "\"id\":\"e1\",\"type\":\"TEXT\",\"x\":40,\"y\":40,\"w\":450,\"h\":60,"
                + "\"content\":\"{{a.b | default:\\\"—\\\"}} {{c.d}}\"}]}");

        var result = renderer().renderWithWarnings(layout, json("{}"), false, null);

        assertFalse(result.warnings().contains("a.b"), "a defaulted placeholder was reported");
        assertTrue(result.warnings().contains("c.d"), "an undefaulted miss went unreported");
    }
}
