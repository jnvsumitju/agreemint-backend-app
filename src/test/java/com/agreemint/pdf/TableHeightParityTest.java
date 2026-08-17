package com.agreemint.pdf;

import com.agreemint.config.PixelParityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A table must occupy exactly the height it was authored at.
 *
 * <p>{@code Cell.setHeight} is content-box in iText — padding and border are
 * added outside it. Handing each row its raw share of the authored height
 * therefore printed the table {@code rowCount × (2×padding + border)} taller
 * than its box: a 118pt five-row table rendered 143pt. Because tables are
 * anchored at their bottom edge, the excess grew <em>upward</em> and overlapped
 * whatever sat above — on the GST invoice it covered the reverse-charge band,
 * clipping text the canvas showed perfectly clear.
 *
 * <p>The measurement endpoint could never have caught it: it derives row
 * heights from cell <em>content</em> (16.6pt/row here), while the renderer
 * derives them from the authored height, so the two were never comparable.
 * That is why this asserts the renderer's own distribution rather than
 * comparing the two paths.
 */
class TableHeightParityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private PdfRendererService renderer(boolean parity) {
        PixelParityProperties flag = new PixelParityProperties();
        flag.setEnabled(parity);
        return new PdfRendererService(
                mapper, new LayoutBehaviourResolver(mapper), null, flag, "https://crixaa.test");
    }

    /** Sum of OUTER row heights — what the page actually gives up to the table. */
    private float outerTotal(PdfRendererService svc, float[] contentHeights) {
        float total = 0;
        for (float h : contentHeights) total += h + svc.rowChromePt();
        return total;
    }

    @Test
    void outerRowHeightsSumToTheAuthoredHeight() {
        PdfRendererService svc = renderer(true);
        // The GST invoice's line-items table: 118pt, header + 4 equal body rows.
        float[] weights = {1f, 1f, 1f, 1f};
        float[] heights = svc.distributeRowHeights(118f, weights, 5f);

        assertEquals(5, heights.length, "header + 4 body rows");
        assertEquals(118f, outerTotal(svc, heights), 0.05f,
                "the rendered table must occupy exactly its authored box");
    }

    @Test
    void unevenRowWeightsStillSumToTheAuthoredHeight() {
        PdfRendererService svc = renderer(true);
        float[] weights = {2f, 1f, 3f};
        float weightSum = 1f + 2f + 1f + 3f;
        float[] heights = svc.distributeRowHeights(200f, weights, weightSum);

        assertEquals(200f, outerTotal(svc, heights), 0.05f);
        // And the weighting must survive the chrome subtraction: the 3-weight
        // row stays the tallest, the 1-weight row the shortest.
        assertTrue(heights[3] > heights[1], "weight 3 row should exceed weight 2 row");
        assertTrue(heights[1] > heights[2], "weight 2 row should exceed weight 1 row");
    }

    @Test
    void legacyModeUsesItsOwnChromeAndStillBalances() {
        // Off the parity flag the padding is 4pt/side and the border 0.5pt.
        // The invariant has to hold there too, or turning the flag off would
        // reintroduce the overlap.
        PdfRendererService svc = renderer(false);
        float[] weights = {1f, 1f};
        float[] heights = svc.distributeRowHeights(90f, weights, 3f);
        assertEquals(90f, outerTotal(svc, heights), 0.05f);
    }

    @Test
    void noAuthoredHeightMeansLetItextSizeToContent() {
        PdfRendererService svc = renderer(true);
        float[] heights = svc.distributeRowHeights(0f, new float[]{1f, 1f}, 3f);
        for (float h : heights) {
            assertEquals(0f, h, 0.001f, "zero tells the caller to fall back to content sizing");
        }
    }

    @Test
    void aTableTooShortForItsChromeDoesNotProduceNegativeRows() {
        // 4pt across 3 rows cannot fit even the padding. Rows must clamp to a
        // positive height rather than going negative, which iText would either
        // reject or render as a collapsed, overlapping mess.
        PdfRendererService svc = renderer(true);
        float[] heights = svc.distributeRowHeights(4f, new float[]{1f, 1f}, 3f);
        for (float h : heights) {
            assertTrue(h > 0f, "row height must stay positive, got " + h);
        }
    }
}
