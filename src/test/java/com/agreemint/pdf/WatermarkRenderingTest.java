package com.agreemint.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.agreemint.config.PixelParityProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The free-plan watermark: present when asked for, absent otherwise, and
 * non-disturbing to the content around it.
 */
class WatermarkRenderingTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String LAYOUT = """
        {"page":{"preset":"A4","orientation":"portrait"},
         "pages":[{"elements":[
           {"id":"t1","type":"TEXT","x":50,"y":50,"width":300,"height":40,"content":"Hello world","style":{"fontSize":12}}
         ]},
         {"elements":[
           {"id":"t2","type":"TEXT","x":50,"y":50,"width":300,"height":40,"content":"Second page","style":{"fontSize":12}}
         ]}]}
        """;

    private static PdfFontRegistry registry;

    @BeforeAll
    static void loadRegistry() {
        registry = new PdfFontRegistry();
        // @PostConstruct is not run by a bare `new`, and createFont returns
        // null until the programs are loaded.
        ReflectionTestUtils.invokeMethod(registry, "loadPrograms");
        assertTrue(registry.isFullyLoaded(), "TTFs must be present under classpath:fonts/");
    }

    private PdfRendererService service() {
        PixelParityProperties flag = new PixelParityProperties();
        flag.setEnabled(true);
        return new PdfRendererService(M, new LayoutBehaviourResolver(M), registry, flag);
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

    @Test
    void watermarkAppearsOnEveryPageWhenRequested() throws Exception {
        JsonNode layout = M.readTree(LAYOUT);
        byte[] pdf = service().render(layout, M.createObjectNode(), true);

        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            assertEquals(2, doc.getNumberOfPages(), "both pages should render");
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                String page = PdfTextExtractor.getTextFromPage(doc.getPage(i));
                assertTrue(page.contains("Crixaa"), "page " + i + " should carry the watermark");
            }
        }
    }

    @Test
    void noWatermarkByDefault() throws Exception {
        JsonNode layout = M.readTree(LAYOUT);
        byte[] pdf = service().render(layout, M.createObjectNode());
        assertFalse(textOf(pdf).contains("Crixaa"),
                "the two-arg render must stay watermark-free — parity tests depend on it");
    }

    @Test
    void watermarkDoesNotDisturbContent() throws Exception {
        JsonNode layout = M.readTree(LAYOUT);
        String clean = textOf(service().render(layout, M.createObjectNode(), false));
        String marked = textOf(service().render(layout, M.createObjectNode(), true));

        // Every bit of real content must survive the stamp unchanged.
        assertTrue(clean.contains("Hello world"), "sanity: the fixture must render text");
        assertTrue(marked.contains("Hello world"), "content must survive the watermark");
        assertTrue(marked.contains("Second page"), "page 2 content must survive too");
    }
}
