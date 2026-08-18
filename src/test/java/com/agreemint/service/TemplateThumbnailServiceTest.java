package com.agreemint.service;

import com.agreemint.config.PixelParityProperties;
import com.agreemint.pdf.LayoutBehaviourResolver;
import com.agreemint.pdf.PdfFontRegistry;
import com.agreemint.pdf.PdfRendererService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The thumbnail is the document, not a picture of the canvas.
 *
 * <p>Rendering goes through the same {@link PdfRendererService} that produces
 * real PDFs and is then rasterised, so a preview cannot drift from the output
 * the way a canvas capture would — the canvas is explicitly not pixel-identical
 * to the PDF.
 *
 * <p>The other property under test is that failure is survivable. A thumbnail
 * is derived and disposable; a commit is not. Every path here returns null
 * rather than throwing, because the caller's real work must not depend on an
 * image rendering.
 */
class TemplateThumbnailServiceTest {

    private static PdfFontRegistry registry;
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void loadFonts() {
        registry = new PdfFontRegistry();
        ReflectionTestUtils.invokeMethod(registry, "loadPrograms");
    }

    private TemplateThumbnailService service() {
        PixelParityProperties flag = new PixelParityProperties();
        flag.setEnabled(true);
        PdfRendererService renderer = new PdfRendererService(
                mapper, new LayoutBehaviourResolver(mapper), registry, flag, "https://crixaa.test");
        return newService(renderer, mock(R2StorageService.class));
    }

    /** Collaborators the thumbnail paths need but these tests do not exercise. */
    private static TemplateThumbnailService newService(PdfRendererService renderer, R2StorageService storage) {
        return new TemplateThumbnailService(
                renderer,
                storage,
                mock(com.agreemint.repository.TemplateRepository.class),
                mock(com.agreemint.repository.OrganizationRepository.class),
                "crixaa");
    }

    /** A real shipped layout, so this exercises the actual render path. */
    private JsonNode realLayout() throws Exception {
        Path p = Path.of("..", "agreemint-frontend-app", "src", "try-templates",
                "free-gst-invoice-template.json");
        return mapper.readTree(Files.readString(p)).path("layout");
    }

    @Test
    void producesADecodablePngFromARealTemplate() throws Exception {
        byte[] png = service().renderPng(realLayout(), JsonNodeFactory.instance.objectNode());

        assertNotNull(png, "a valid A4 layout must produce an image");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img, "the bytes must decode as an image, not merely be non-empty");
        assertTrue(img.getWidth() > 0 && img.getHeight() > 0);
    }

    @Test
    void isDownscaledAndKeepsPortraitAspect() throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(
                service().renderPng(realLayout(), JsonNodeFactory.instance.objectNode())));

        // Bounded so a full-page render is not stored per template per commit.
        assertTrue(Math.max(img.getWidth(), img.getHeight()) <= 600,
                "long edge should be capped, got " + img.getWidth() + "x" + img.getHeight());
        // A4 portrait is taller than wide; a squashed thumbnail means the scale
        // maths lost the aspect ratio.
        assertTrue(img.getHeight() > img.getWidth(), "A4 portrait must stay portrait");
        double ratio = (double) img.getHeight() / img.getWidth();
        assertEquals(842.0 / 595.0, ratio, 0.02, "aspect ratio must survive the downscale");
    }

    @Test
    void aMalformedPageRendersBlankRatherThanFailing() {
        // Documents what the renderer actually does, which is not what I first
        // assumed: it is tolerant, so an unrecognised page draws nothing and
        // still produces a valid A4 PDF. A blank thumbnail is the honest result
        // for a layout with nothing renderable in it.
        ObjectNode nonsense = mapper.createObjectNode();
        nonsense.putArray("pages").addObject().put("not", "a page");
        assertNotNull(service().renderPng(nonsense, null));
    }

    @Test
    void aRendererFailureReturnsNullRatherThanThrowing() throws Exception {
        // The contract that matters: a commit must not fail because an image
        // did not render. Exercised with a renderer that genuinely throws,
        // since a malformed layout does not.
        PdfRendererService boom = mock(PdfRendererService.class);
        org.mockito.Mockito.when(boom.render(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("renderer exploded"));

        var svc = newService(boom, mock(R2StorageService.class));
        assertNull(svc.renderPng(mapper.createObjectNode(), null));
    }

    @Test
    void anUnreadablePdfReturnsNullRatherThanThrowing() throws Exception {
        // Rasterisation is the other half that can fail — PDFBox raises on
        // bytes that are not a PDF, and that must be survivable too.
        PdfRendererService garbage = mock(PdfRendererService.class);
        org.mockito.Mockito.when(garbage.render(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn("not a pdf at all".getBytes());

        var svc = newService(garbage, mock(R2StorageService.class));
        assertNull(svc.renderPng(mapper.createObjectNode(), null));
    }

    @Test
    void aNullLayoutIsNotAnError() {
        assertNull(service().renderPng(null, null));
    }

    @Test
    void theKeyIsStablePerTemplateSoRedrawsOverwrite() {
        UUID id = UUID.randomUUID();
        // Not versioned on purpose: one object per template per sixty seconds of
        // editing would grow without bound, and nothing ever reads an old one.
        assertEquals(TemplateThumbnailService.privateKey(id), TemplateThumbnailService.privateKey(id));
        assertTrue(TemplateThumbnailService.privateKey(id).contains(id.toString()));
    }

    @Test
    void uploadFailureIsSwallowed() {
        R2StorageService failing = mock(R2StorageService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("R2 down"))
                .when(failing).putThumbnail(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        PixelParityProperties flag = new PixelParityProperties();
        flag.setEnabled(true);
        var svc = newService(
                new PdfRendererService(mapper, new LayoutBehaviourResolver(mapper), registry, flag, "x"),
                failing);

        assertNull(svc.putPrivate(UUID.randomUUID(), new byte[] { 1, 2, 3 }),
                "storage being down must not propagate into a commit");
    }
}
