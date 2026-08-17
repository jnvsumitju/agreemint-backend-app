package com.agreemint.pdf;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Stamps the visible verification mark: a QR code and the printable code, in
 * the bottom-left corner of every page.
 *
 * <p>Drawn straight onto the page canvas, exactly as the free-plan watermark
 * is, rather than through the layout engine. That is what guarantees it cannot
 * move anything: no Paragraph is created, no property is resolved, and the
 * layout has already been decided by the time this runs. A mark that nudged the
 * document's own content would be worse than no mark.
 *
 * <p>The QR is emitted as <b>vector rectangles</b>, one per dark module, rather
 * than as an embedded raster. It costs a few hundred path operations and in
 * return the code is exact at every zoom level and at any printer resolution —
 * which matters, because a QR that fails to scan off paper is the one case this
 * feature exists to serve.
 */
final class VerificationMarkRenderer {

    private static final Logger log = LoggerFactory.getLogger(VerificationMarkRenderer.class);

    /** Printed size of the QR. Large enough to scan from a phone at arm's length. */
    private static final float QR_SIZE_PT = 42f;
    private static final float MARGIN_PT = 18f;
    private static final float LABEL_SIZE_PT = 6.5f;
    private static final float CODE_SIZE_PT = 8f;

    private static final DeviceRgb INK = new DeviceRgb(17, 24, 39);
    private static final DeviceRgb MUTED = new DeviceRgb(107, 114, 128);

    private final PdfFontRegistry fontRegistry;

    VerificationMarkRenderer(PdfFontRegistry fontRegistry) {
        this.fontRegistry = fontRegistry;
    }

    void stamp(PdfDocument pdfDoc, VerificationMark mark, String verifyBaseUrl) {
        if (mark == null || mark.code() == null || mark.code().isBlank()) return;

        PdfFont regular = fontRegistry.createFont(PdfFontRegistry.FAMILY_SANS, false, false);
        PdfFont bold = fontRegistry.createFont(PdfFontRegistry.FAMILY_SANS, true, false);
        if (regular == null || bold == null) {
            // Same call as the watermark makes: a missing mark is a lost
            // feature, a failed render is a broken product.
            log.warn("Skipping verification mark — font unavailable");
            return;
        }

        String url = verifyBaseUrl + "/verify/" + mark.code().replace("-", "");
        BitMatrix qr = encode(url);
        if (qr == null) return;

        for (int pageNumber = 1; pageNumber <= pdfDoc.getNumberOfPages(); pageNumber++) {
            PdfPage page = pdfDoc.getPage(pageNumber);
            Rectangle box = page.getPageSize();
            PdfCanvas canvas = new PdfCanvas(page);

            float x = box.getLeft() + MARGIN_PT;
            float y = box.getBottom() + MARGIN_PT;

            canvas.saveState();
            drawQr(canvas, qr, x, y, QR_SIZE_PT);

            float textX = x + QR_SIZE_PT + 8f;
            canvas.beginText()
                    .setFontAndSize(regular, LABEL_SIZE_PT)
                    .setFillColor(MUTED)
                    .moveText(textX, y + QR_SIZE_PT - LABEL_SIZE_PT - 1f)
                    .showText("VERIFY THIS DOCUMENT")
                    .endText();

            canvas.beginText()
                    .setFontAndSize(bold, CODE_SIZE_PT)
                    .setFillColor(INK)
                    .moveText(textX, y + QR_SIZE_PT - LABEL_SIZE_PT - CODE_SIZE_PT - 6f)
                    .showText(mark.code())
                    .endText();

            canvas.beginText()
                    .setFontAndSize(regular, LABEL_SIZE_PT)
                    .setFillColor(MUTED)
                    .moveText(textX, y + 2f)
                    .showText(hostOf(verifyBaseUrl) + "/verify")
                    .endText();

            canvas.restoreState();
        }
    }

    private BitMatrix encode(String url) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            // M corrects ~15% damage — the right level for something that will
            // be printed, folded and photographed. H would be more robust but
            // needs a denser grid, and density is what actually breaks a scan
            // at this physical size.
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            // Zero quiet zone here; the surrounding page whitespace already
            // provides one, and ZXing's default border would shrink the modules.
            hints.put(EncodeHintType.MARGIN, 0);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            return new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException | IllegalArgumentException e) {
            log.warn("Skipping verification mark — QR encoding failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Paint the dark modules as filled rectangles.
     *
     * <p>Runs of adjacent modules are merged into a single rectangle per row.
     * Without that a typical code emits over a thousand tiny squares, and some
     * viewers leave hairline seams between abutting fills — which reads as
     * noise to a scanner.
     */
    private void drawQr(PdfCanvas canvas, BitMatrix matrix, float x, float y, float size) {
        int modules = matrix.getWidth();
        float module = size / modules;

        canvas.setFillColor(com.itextpdf.kernel.colors.ColorConstants.BLACK);
        for (int row = 0; row < modules; row++) {
            int runStart = -1;
            for (int col = 0; col <= modules; col++) {
                boolean dark = col < modules && matrix.get(col, row);
                if (dark && runStart < 0) {
                    runStart = col;
                } else if (!dark && runStart >= 0) {
                    // PDF's origin is bottom-left; the matrix counts rows from
                    // the top, so the row index is flipped.
                    float rx = x + runStart * module;
                    float ry = y + size - (row + 1) * module;
                    canvas.rectangle(rx, ry, module * (col - runStart), module);
                    runStart = -1;
                }
            }
        }
        canvas.fill();
    }

    /** `https://crixaa.com` → `crixaa.com`, for printing without the scheme. */
    private static String hostOf(String baseUrl) {
        return baseUrl.replaceFirst("^https?://", "").replaceFirst("/+$", "");
    }
}
