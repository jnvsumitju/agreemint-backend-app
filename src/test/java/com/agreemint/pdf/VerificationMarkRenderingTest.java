package com.agreemint.pdf;

import com.agreemint.config.PixelParityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The visible verification mark.
 *
 * <p>Two things have to be true of it, and they pull in opposite directions:
 * it has to appear, and it must not move anything. The second is why it is
 * painted straight onto the page canvas rather than added through the layout
 * engine, and why the "unmarked and marked documents lay text out identically"
 * test below is the important one.
 */
class VerificationMarkRenderingTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String CODE = "8FK2M-9QTX4-M7PWR";
    private static final UUID DOC_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static PdfFontRegistry registry;

    @BeforeAll
    static void loadRegistry() {
        registry = new PdfFontRegistry();
        // @PostConstruct does not run for a bare `new`, and createFont returns
        // null until the programs are loaded — which would silently skip the
        // mark and make every assertion here pass for the wrong reason.
        ReflectionTestUtils.invokeMethod(registry, "loadPrograms");
        assertTrue(registry.isFullyLoaded(), "TTFs must be present under classpath:fonts/");
    }

    private static PdfRendererService renderer() {
        return new PdfRendererService(M, new LayoutBehaviourResolver(M), registry,
                new PixelParityProperties(), "https://crixaa.test");
    }

    /** One text element, so there is real content to compare against. */
    private static com.fasterxml.jackson.databind.JsonNode layout() throws Exception {
        return M.readTree("""
            {
              "page": {"size": "A4", "margin": 40},
              "pages": [{
                "id": "page_1",
                "elements": [
                  {"id": "t1", "type": "TEXT", "x": 60, "y": 80,
                   "width": 400, "height": 40, "content": "Invoice total 1200"}
                ]
              }]
            }
            """);
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            StringBuilder out = new StringBuilder();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                out.append(PdfTextExtractor.getTextFromPage(doc.getPage(p)));
            }
            return out.toString();
        }
    }

    @Test
    void theCodeIsPrintedWhenTheMarkIsVisible() throws Exception {
        byte[] pdf = renderer().render(layout(), M.createObjectNode(), false,
                new VerificationMark(DOC_ID, CODE, true));

        String text = textOf(pdf);
        assertTrue(text.contains(CODE), "the printed code should be on the page");
        assertTrue(text.contains("crixaa.test/verify"), "the page should say where to check it");
    }

    @Test
    void nothingIsPrintedWhenTheMarkIsHidden() throws Exception {
        byte[] pdf = renderer().render(layout(), M.createObjectNode(), false,
                VerificationMark.hidden(DOC_ID, CODE));

        assertFalse(textOf(pdf).contains(CODE),
                "a hidden mark must not put the code on the page");
    }

    @Test
    void theIdentityIsInTheMetadataEitherWay() throws Exception {
        // The point of the metadata copy: even a document with no visible mark
        // can be traced back to what it claims to be.
        byte[] pdf = renderer().render(layout(), M.createObjectNode(), false,
                VerificationMark.hidden(DOC_ID, CODE));

        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            assertEquals(DOC_ID.toString(), doc.getDocumentInfo().getMoreInfo("CrixaaDocumentId"));
            assertEquals(CODE, doc.getDocumentInfo().getMoreInfo("CrixaaVerificationCode"));
            assertEquals("https://crixaa.test/verify",
                    doc.getDocumentInfo().getMoreInfo("CrixaaVerifyUrl"));
        }
    }

    @Test
    void aPreviewCarriesNoIdentityAtAll() throws Exception {
        // Previews have no document row. A code that resolves to nothing would
        // be worse than none — it would make a real check fail.
        byte[] pdf = renderer().render(layout(), M.createObjectNode(), false, null);

        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            assertNull(doc.getDocumentInfo().getMoreInfo("CrixaaDocumentId"));
            assertNull(doc.getDocumentInfo().getMoreInfo("CrixaaVerificationCode"));
        }
    }

    /**
     * The one that matters. A mark that shifted the document's own content
     * would corrupt every template it was enabled on, and it would do so
     * quietly — the page would still render, just wrong.
     */
    @Test
    void theMarkDoesNotMoveTheDocumentsOwnContent() throws Exception {
        byte[] unmarked = renderer().render(layout(), M.createObjectNode(), false, null);
        byte[] marked = renderer().render(layout(), M.createObjectNode(), false,
                new VerificationMark(DOC_ID, CODE, true));

        String unmarkedText = textOf(unmarked);
        String markedText = textOf(marked);

        assertTrue(unmarkedText.contains("Invoice total 1200"));
        // The document's own text survives verbatim, and the only difference is
        // what the mark added.
        assertTrue(markedText.contains("Invoice total 1200"));
        assertEquals(unmarkedText.trim(),
                markedText.replace(CODE, "")
                        .replace("VERIFY THIS DOCUMENT", "")
                        .replace("crixaa.test/verify", "")
                        .trim());
    }

    @Test
    void aMarkWithNoCodeIsSkippedRatherThanDrawnEmpty() throws Exception {
        byte[] pdf = renderer().render(layout(), M.createObjectNode(), false,
                new VerificationMark(DOC_ID, null, true));

        assertFalse(textOf(pdf).contains("VERIFY THIS DOCUMENT"));
    }
}
