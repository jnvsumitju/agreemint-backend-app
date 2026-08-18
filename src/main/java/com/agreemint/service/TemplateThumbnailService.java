package com.agreemint.service;

import com.agreemint.pdf.PdfRendererService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

/**
 * Turns a template layout into a small preview image.
 *
 * <p>Renders the layout to a PDF through the same {@link PdfRendererService}
 * that produces real documents, then rasterises page one. That matters more
 * than it sounds: a thumbnail captured from the editor canvas would show what
 * the canvas draws, and the canvas is explicitly not pixel-identical to the
 * PDF. This way the preview is the document.
 *
 * <p>It also means a thumbnail can be made with no browser involved — for a
 * commit through the v1 API, for the seeded first-party templates that nobody
 * ever opens in an editor, and for backfilling templates that already exist.
 *
 * <p>Every method here is best-effort. A thumbnail is a derived, disposable
 * image that can always be re-made from the layout; a caller's real work —
 * committing a version — must never fail because an image did not render.
 */
@Service
public class TemplateThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(TemplateThumbnailService.class);

    /**
     * Long edge of the stored image, in pixels.
     *
     * <p>A card in the templates list is roughly 300px wide and may be shown on
     * a 2× display, so 600 keeps it crisp there without storing a full-page
     * render per template per commit.
     */
    private static final int MAX_EDGE = 600;

    /**
     * Long edge of the world-readable copy, in pixels.
     *
     * <p>Larger than the private one because it is used somewhere else: the
     * card grid on crixaa.com crops these to 4:3 and top-aligns them, so only
     * the upper ~56% of the page survives the crop and has to fill a card
     * around 380 CSS px wide. At 600 that lands at roughly 1:1 and looks soft
     * on any retina display; 900 gives it something to downscale from.
     */
    private static final int PUBLIC_MAX_EDGE = 900;

    /**
     * DPI to rasterise at. 72 is one image pixel per PDF point, i.e. A4 comes
     * out 595×842 — below {@link #PUBLIC_MAX_EDGE}, so the public copy would be
     * an upscale of the private one rather than a sharper render. 150 renders
     * A4 at 1240×1754, which both sizes then downscale from.
     */
    private static final float RASTER_DPI = 150f;

    private final PdfRendererService renderer;
    private final R2StorageService storage;
    private final com.agreemint.repository.TemplateRepository templateRepo;
    private final com.agreemint.repository.OrganizationRepository orgRepo;
    private final String publisherSlug;

    public TemplateThumbnailService(
            PdfRendererService renderer,
            R2StorageService storage,
            com.agreemint.repository.TemplateRepository templateRepo,
            com.agreemint.repository.OrganizationRepository orgRepo,
            @org.springframework.beans.factory.annotation.Value("${agreemint.publisher.slug:crixaa}")
            String publisherSlug) {
        this.renderer = renderer;
        this.storage = storage;
        this.templateRepo = templateRepo;
        this.orgRepo = orgRepo;
        this.publisherSlug = publisherSlug;
    }

    /**
     * Refresh the in-progress preview for a template being edited.
     *
     * <p>Separate key from the committed thumbnail: an edit in progress must not
     * replace the image the first-party templates publish to crixaa.com. It
     * also never mirrors publicly — an uncommitted edit is not a published
     * document, whoever owns it.
     *
     * <p>Never throws, for the same reason {@link #captureCommitted} does not:
     * this runs from a sixty-second poll in every open editor, and a storage
     * blip should not put an error on the screen of someone who merely left a
     * tab open.
     */
    @org.springframework.transaction.annotation.Transactional
    public void captureDraft(UUID templateId, JsonNode layoutJson, JsonNode data) {
        try {
            byte[] png = renderPng(layoutJson, data);
            if (png == null) return;
            String key = putPrivate(templateId, png);
            if (key == null) return;
            templateRepo.findById(templateId).ifPresent(t -> {
                t.setDraftThumbnailKey(key);
                t.setThumbnailUpdatedAt(java.time.Instant.now());
                templateRepo.save(t);
            });
        } catch (Throwable th) {
            log.warn("[thumbnail] Draft capture failed for {}: {}", templateId, th.toString());
        }
    }

    /**
     * Render the preview for a newly committed version.
     *
     * <p>Never throws. A thumbnail is derived and can be re-made from the
     * layout at any time; a commit is the author's actual work. Losing a commit
     * because an image failed to rasterise would be an absurd trade.
     *
     * <p>Clears the draft preview, because after a commit the draft no longer
     * exists — {@code commitDraft} deletes the row — so a stale in-progress
     * image would outlive the thing it depicted.
     */
    @org.springframework.transaction.annotation.Transactional
    public void captureCommitted(UUID templateId, JsonNode layoutJson, JsonNode data) {
        try {
            BufferedImage page = rasterise(layoutJson, data);
            byte[] png = encode(page, MAX_EDGE);
            if (png == null) return;
            String key = putPrivate(templateId, png);
            if (key == null) return;

            templateRepo.findById(templateId).ifPresent(t -> {
                t.setThumbnailKey(key);
                t.setDraftThumbnailKey(null);
                t.setThumbnailUpdatedAt(java.time.Instant.now());
                templateRepo.save(t);
                publishIfFirstParty(t, page);
            });
        } catch (Throwable th) {
            log.warn("[thumbnail] Commit capture failed for {}: {}", templateId, th.toString());
        }
    }

    /**
     * Mirror a committed thumbnail into the world-readable bucket, but only for
     * the publisher org.
     *
     * <p>The check is on the template's OWNING ORG, not on anything the caller
     * supplied. A customer's template preview in a public bucket would be
     * readable by anyone with the URL, and this condition is the only thing
     * standing between the two.
     *
     * <p>Two conditions, not one. The org check is the privacy boundary. The
     * slug is what makes the object addressable: crixaa.com builds the URL from
     * the slug in its own frontmatter and cannot learn a UUID, so a template
     * without one has no page to appear on and is not worth publishing.
     *
     * <p>Encoded larger than the private copy — see {@link #PUBLIC_MAX_EDGE}.
     */
    private void publishIfFirstParty(com.agreemint.domain.Template t, BufferedImage page) {
        if (t.getOrgId() == null) return;
        String slug = t.getPublicSlug();
        if (slug == null || slug.isBlank()) return;
        boolean firstParty = orgRepo.findById(t.getOrgId())
                .map(o -> publisherSlug.equalsIgnoreCase(o.getSlug()))
                .orElse(false);
        if (!firstParty) return;
        String url = putPublic(slug, encode(page, PUBLIC_MAX_EDGE));
        if (url != null) {
            log.info("[thumbnail] Published first-party thumbnail for '{}' -> {}", t.getName(), url);
        }
    }

    /**
     * Render a layout and rasterise page one at {@link #RASTER_DPI}.
     *
     * <p>Kept separate from encoding so a commit rasterises once and derives
     * both the private and the public size from the same image. Rendering the
     * PDF twice to get two sizes would double the expensive half of this.
     *
     * @return the page image, or null when anything at all went wrong
     */
    private BufferedImage rasterise(JsonNode layoutJson, JsonNode data) {
        if (layoutJson == null || layoutJson.isNull()) return null;
        try {
            byte[] pdf = renderer.render(layoutJson, data == null ? JsonNodeFactory.instance.objectNode() : data);
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                if (doc.getNumberOfPages() == 0) return null;
                return new PDFRenderer(doc).renderImageWithDPI(0, RASTER_DPI);
            }
        } catch (Throwable t) {
            // Throwable, not Exception: image rasterisation can raise
            // OutOfMemoryError or AWT errors on a malformed page, and none of
            // that is worth failing a commit over.
            log.warn("[thumbnail] Could not render: {}", t.toString());
            return null;
        }
    }

    /** Downscale to {@code maxEdge} and encode. @return PNG bytes, or null. */
    private static byte[] encode(BufferedImage page, int maxEdge) {
        if (page == null) return null;
        try {
            return toPng(scaleToFit(page, maxEdge));
        } catch (Throwable t) {
            log.warn("[thumbnail] Could not encode: {}", t.toString());
            return null;
        }
    }

    /**
     * Render a layout into a list-sized preview.
     *
     * @return PNG bytes, or null when anything at all went wrong
     */
    public byte[] renderPng(JsonNode layoutJson, JsonNode data) {
        return encode(rasterise(layoutJson, data), MAX_EDGE);
    }

    /** Store a private thumbnail for a template. @return the object key, or null. */
    public String putPrivate(UUID templateId, byte[] png) {
        if (png == null) return null;
        String key = privateKey(templateId);
        try {
            storage.putThumbnail(key, png, "image/png");
            return key;
        } catch (Exception e) {
            log.warn("[thumbnail] Upload failed for template {}: {}", templateId, e.toString());
            return null;
        }
    }

    /**
     * Store a world-readable thumbnail. Only ever called for the first-party
     * publisher org — see {@code R2Properties.bucketThumbnailsPublic}.
     *
     * @return the permanent public URL, or null
     */
    public String putPublic(String slugOrId, byte[] png) {
        if (png == null) return null;
        try {
            return storage.putPublicThumbnail("templates/" + slugOrId + ".png", png, "image/png");
        } catch (Exception e) {
            log.warn("[thumbnail] Public upload failed for {}: {}", slugOrId, e.toString());
            return null;
        }
    }

    /**
     * Stable key per template, so a new thumbnail overwrites the old one.
     *
     * <p>Deliberately not versioned: keeping every draft capture would grow
     * without bound — one object per template per sixty seconds of editing —
     * and nothing ever reads an older one.
     */
    public static String privateKey(UUID templateId) {
        return "templates/" + templateId + ".png";
    }

    /** Downscale so the long edge is {@code maxEdge}, preserving aspect. */
    private static BufferedImage scaleToFit(BufferedImage src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        double scale = (double) maxEdge / Math.max(w, h);
        if (scale >= 1.0) return src;

        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            // Bilinear over nearest-neighbour: a downscaled page is mostly small
            // text, and nearest-neighbour turns that into noise.
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] toPng(BufferedImage img) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
