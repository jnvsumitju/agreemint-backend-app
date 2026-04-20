package com.agreemint.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 6c — rasterises a CSS-style drop shadow into a PNG-with-alpha that
 * iText can embed as an {@link com.itextpdf.layout.element.Image} underneath
 * the shadowed element. Renders at 2× DPI so the shadow stays crisp at
 * typical PDF viewer zooms (100%–200%).
 *
 * <p>Trade-off vs true PDF SMask: SMask with a blurred transparency group
 * would stay vector-crisp at any zoom. iText 7.2.5 doesn't ship a Gaussian
 * blur primitive for graphics-state masks, so implementing SMask parity-
 * quality needs custom PDF-spec work (blurred alpha-mask XObject chain).
 * Rasterising at 2× DPI is the pragmatic stop-gap; the SMask upgrade is
 * flagged in the plan doc as a follow-on sub-phase.
 *
 * <p>Caches generated PNGs keyed by {@code (w, h, offsetX, offsetY, blur,
 * color, shape)} so repeated elements with identical shadows don't rasterise
 * N times.
 */
final class ShadowRasterizer {

    private static final Logger log = LoggerFactory.getLogger(ShadowRasterizer.class);

    /** Render at 2× the declared size so consumers viewing the PDF at ~200% zoom still see crisp edges. */
    private static final int DPI_SCALE = 2;

    /** Clamp author-supplied blur radius to protect against O(kernel²) latency explosions. */
    private static final float MAX_BLUR = 24f;

    private static final ConcurrentHashMap<String, byte[]> CACHE = new ConcurrentHashMap<>();

    private ShadowRasterizer() {}

    /** Shape flavours the rasteriser knows how to draw. */
    enum Shape { RECTANGLE, ROUNDED_RECTANGLE, ELLIPSE }

    /**
     * Build a PNG for the shadow. Dimensions are in pt; the caller places the
     * resulting Image at the same pt dimensions and iText scales the 2× pixel
     * buffer down on render.
     *
     * @return PNG bytes (with alpha), or {@code null} when the shadow spec is
     *         invalid (missing color / non-positive size).
     */
    static byte[] rasterize(float w, float h, JsonNode shadow, Shape shape, float cornerRadiusPt) {
        if (shadow == null || shadow.isMissingNode() || shadow.isNull()) return null;
        float offsetX = (float) shadow.path("offsetX").asDouble(0);
        float offsetY = (float) shadow.path("offsetY").asDouble(0);
        float blur = (float) Math.min(MAX_BLUR, Math.max(0, shadow.path("blur").asDouble(4)));
        String colorStr = shadow.path("color").asText("rgba(0,0,0,0.25)");
        Color color = parseCssColor(colorStr);
        if (color == null || w <= 0 || h <= 0) return null;

        String cacheKey = w + "|" + h + "|" + offsetX + "|" + offsetY + "|" + blur + "|"
                + colorStr + "|" + shape + "|" + cornerRadiusPt;
        byte[] cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        // Padding = blur×2 on each side so the blurred edge has room to fall
        // off smoothly. Offsets push the shape within the canvas; we then
        // blur + export.
        int padPx = Math.round(blur * 2f * DPI_SCALE);
        int pxW = Math.round(w * DPI_SCALE) + padPx * 2;
        int pxH = Math.round(h * DPI_SCALE) + padPx * 2;

        BufferedImage img = new BufferedImage(pxW, pxH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.Src);
            g.setColor(color);
            int shapeX = padPx + Math.round(offsetX * DPI_SCALE);
            int shapeY = padPx + Math.round(offsetY * DPI_SCALE);
            int shapeW = Math.round(w * DPI_SCALE);
            int shapeH = Math.round(h * DPI_SCALE);
            switch (shape) {
                case RECTANGLE -> g.fillRect(shapeX, shapeY, shapeW, shapeH);
                case ROUNDED_RECTANGLE -> {
                    int r = Math.round(cornerRadiusPt * DPI_SCALE);
                    g.fillRoundRect(shapeX, shapeY, shapeW, shapeH, r * 2, r * 2);
                }
                case ELLIPSE -> g.fillOval(shapeX, shapeY, shapeW, shapeH);
            }
        } finally {
            g.dispose();
        }

        BufferedImage blurred = blur > 0f ? gaussianBlur(img, Math.round(blur * DPI_SCALE)) : img;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(blurred, "png", baos);
            byte[] bytes = baos.toByteArray();
            CACHE.putIfAbsent(cacheKey, bytes);
            return bytes;
        } catch (Exception e) {
            log.warn("ShadowRasterizer PNG encode failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Two-pass separable Gaussian blur for O(kernel) per pixel instead of
     * O(kernel²) via `ConvolveOp`. Acceptable overhead at {@link #MAX_BLUR}
     * (24pt × 2× = 48px kernel, ~96 mults/pixel per pass).
     */
    private static BufferedImage gaussianBlur(BufferedImage src, int radiusPx) {
        if (radiusPx <= 0) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = src.getRGB(0, 0, w, h, null, 0, w);
        int[] buf = new int[pixels.length];
        int kernelSize = radiusPx * 2 + 1;
        float sigma = radiusPx / 2f;
        float[] kernel = new float[kernelSize];
        float sum = 0f;
        for (int i = 0; i < kernelSize; i++) {
            int d = i - radiusPx;
            kernel[i] = (float) Math.exp(-(d * d) / (2.0 * sigma * sigma));
            sum += kernel[i];
        }
        for (int i = 0; i < kernelSize; i++) kernel[i] /= sum;

        blurPassHorizontal(pixels, buf, w, h, kernel, radiusPx);
        blurPassVertical(buf, pixels, w, h, kernel, radiusPx);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, pixels, 0, w);
        return out;
    }

    private static void blurPassHorizontal(int[] in, int[] out, int w, int h, float[] k, int r) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float a = 0, rC = 0, gC = 0, bC = 0;
                for (int i = -r; i <= r; i++) {
                    int xi = Math.min(w - 1, Math.max(0, x + i));
                    int rgb = in[y * w + xi];
                    float weight = k[i + r];
                    a += ((rgb >>> 24) & 0xff) * weight;
                    rC += ((rgb >>> 16) & 0xff) * weight;
                    gC += ((rgb >>> 8) & 0xff) * weight;
                    bC += (rgb & 0xff) * weight;
                }
                out[y * w + x] = packArgb(a, rC, gC, bC);
            }
        }
    }

    private static void blurPassVertical(int[] in, int[] out, int w, int h, float[] k, int r) {
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                float a = 0, rC = 0, gC = 0, bC = 0;
                for (int i = -r; i <= r; i++) {
                    int yi = Math.min(h - 1, Math.max(0, y + i));
                    int rgb = in[yi * w + x];
                    float weight = k[i + r];
                    a += ((rgb >>> 24) & 0xff) * weight;
                    rC += ((rgb >>> 16) & 0xff) * weight;
                    gC += ((rgb >>> 8) & 0xff) * weight;
                    bC += (rgb & 0xff) * weight;
                }
                out[y * w + x] = packArgb(a, rC, gC, bC);
            }
        }
    }

    private static int packArgb(float a, float r, float g, float b) {
        int ai = Math.min(255, Math.max(0, Math.round(a)));
        int ri = Math.min(255, Math.max(0, Math.round(r)));
        int gi = Math.min(255, Math.max(0, Math.round(g)));
        int bi = Math.min(255, Math.max(0, Math.round(b)));
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }

    private static Color parseCssColor(String css) {
        if (css == null) return null;
        String s = css.trim();
        if (s.startsWith("#")) {
            try {
                if (s.length() == 7) {
                    int r = Integer.parseInt(s.substring(1, 3), 16);
                    int g = Integer.parseInt(s.substring(3, 5), 16);
                    int b = Integer.parseInt(s.substring(5, 7), 16);
                    return new Color(r, g, b);
                }
            } catch (NumberFormatException ignored) {}
        }
        if (s.startsWith("rgba") || s.startsWith("rgb")) {
            int open = s.indexOf('(');
            int close = s.indexOf(')');
            if (open > 0 && close > open) {
                String[] parts = s.substring(open + 1, close).split(",");
                try {
                    int r = Integer.parseInt(parts[0].trim());
                    int g = Integer.parseInt(parts[1].trim());
                    int b = Integer.parseInt(parts[2].trim());
                    int a = parts.length > 3 ? Math.round(Float.parseFloat(parts[3].trim()) * 255f) : 255;
                    return new Color(Math.min(255, Math.max(0, r)),
                            Math.min(255, Math.max(0, g)),
                            Math.min(255, Math.max(0, b)),
                            Math.min(255, Math.max(0, a)));
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /** Clear the cache — test-only hook. */
    static void clearCache() { CACHE.clear(); }

    /** Approximate cache size — test-only hook. */
    static int cacheSize() { return CACHE.size(); }

    @SuppressWarnings("unused")
    private static String dummyHashForOverride(Object o) { return String.valueOf(Objects.hashCode(o)); }
}
