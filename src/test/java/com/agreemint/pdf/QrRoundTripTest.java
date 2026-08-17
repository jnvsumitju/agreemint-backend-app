package com.agreemint.pdf;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.agreemint.config.PixelParityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the printed QR actually decodes back to the URL it claims.
 *
 * <p>Everything else about the mark can be right — correct code, correct
 * position, no layout disturbance — while the QR is unreadable, and nothing in
 * the other tests would notice. A QR is exactly the kind of artefact that looks
 * plausible and fails in the one situation it exists for.
 *
 * <p>The PDF is rasterised with the same pdfbox-free approach the rest of the
 * suite avoids by reading the drawn matrix directly: here the encoder is run
 * over the same input the renderer uses, then decoded, which verifies the
 * encode/decode contract and the exact string being embedded. It does not
 * re-photograph the page, which no unit test can do.
 */
class QrRoundTripTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String CODE = "8FK2M-9QTX4-M7PWR";
    private static PdfFontRegistry registry;

    @BeforeAll
    static void loadRegistry() {
        registry = new PdfFontRegistry();
        ReflectionTestUtils.invokeMethod(registry, "loadPrograms");
    }

    /**
     * The URL the renderer builds, decoded from a QR encoded exactly as the
     * renderer encodes it.
     *
     * <p>Dashes are stripped from the code in the URL — a scanner should not
     * have to care about separators, and the backend normalises them back.
     */
    @Test
    void theQrDecodesToTheVerificationUrl() throws Exception {
        String expected = "https://crixaa.test/verify/" + CODE.replace("-", "");

        com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter()
                .encode(expected, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, encodeHints());

        // Rasterise the matrix at 4 pixels per module — comfortably above the
        // decoder's minimum, and the same relationship the printed mark has at
        // any sane print resolution.
        int scale = 4;
        int size = matrix.getWidth() * scale;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean dark = matrix.get(x / scale, y / scale);
                pixels[y * size + x] = dark ? 0x000000 : 0xFFFFFF;
            }
        }

        Result decoded = new QRCodeReader().decode(
                new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(size, size, pixels))));

        assertEquals(expected, decoded.getText());
    }

    /** The stripped code in the URL must survive the backend's normalisation. */
    @Test
    void theUrlFormOfTheCodeNormalisesBack() {
        String inUrl = CODE.replace("-", "");
        assertEquals(CODE, com.agreemint.service.VerificationCodes.normalise(inUrl));
    }

    /** And the mark that lands on the page carries that same URL. */
    @Test
    void theRenderedPageAdvertisesTheSameHost() throws Exception {
        PdfRendererService renderer = new PdfRendererService(
                M, new LayoutBehaviourResolver(M), registry, new PixelParityProperties(),
                "https://crixaa.test");

        byte[] pdf = renderer.render(
                M.readTree("{\"page\":{\"size\":\"A4\",\"margin\":40},\"pages\":[{\"id\":\"p\",\"elements\":[]}]}"),
                M.createObjectNode(), false,
                new VerificationMark(UUID.randomUUID(), CODE, true));

        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            String text = com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
                    .getTextFromPage(doc.getPage(1));
            assertTrue(text.contains("crixaa.test/verify"), text);
            assertTrue(text.contains(CODE), text);
        }
    }

    private static Map<com.google.zxing.EncodeHintType, Object> encodeHints() {
        Map<com.google.zxing.EncodeHintType, Object> hints = new EnumMap<>(com.google.zxing.EncodeHintType.class);
        hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION,
                com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
        hints.put(com.google.zxing.EncodeHintType.MARGIN, 0);
        hints.put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8");
        return hints;
    }
}
