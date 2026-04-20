package com.agreemint.pdf;

import com.itextpdf.kernel.font.PdfFont;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-0 contract: every family/weight/style we advertise must load from the
 * bundled TTFs under {@code classpath:fonts/}. An unknown family falls back
 * gracefully to Inter-Regular so the renderer never crashes on a legacy
 * layout with a non-parity family.
 */
class PdfFontRegistryTest {

    @Test
    void loadsAllExpectedFontsFromClasspath() {
        PdfFontRegistry registry = new PdfFontRegistry();
        ReflectionTestUtils.invokeMethod(registry, "loadPrograms");

        assertTrue(registry.isFullyLoaded(),
                "All 12 TTFs (Inter / Source Serif 4 / JetBrains Mono × 4 weights) "
                        + "must be on the classpath under fonts/");

        for (String family : new String[]{
                PdfFontRegistry.FAMILY_SANS,
                PdfFontRegistry.FAMILY_SERIF,
                PdfFontRegistry.FAMILY_MONO}) {
            for (boolean bold : new boolean[]{false, true}) {
                for (boolean italic : new boolean[]{false, true}) {
                    PdfFont font = registry.createFont(family, bold, italic);
                    assertNotNull(font, "Missing font: " + family + " bold=" + bold + " italic=" + italic);
                }
            }
        }
    }

    @Test
    void unknownFamilyFallsBackToInter() {
        PdfFontRegistry registry = new PdfFontRegistry();
        ReflectionTestUtils.invokeMethod(registry, "loadPrograms");

        PdfFont font = registry.createFont("Arial", false, false);
        assertNotNull(font, "Unknown family should fall back to Inter-Regular, not crash");
    }

    @Test
    void unknownFamilyWithNoProgramsReturnsNull() {
        // Simulate the phase-0 no-fonts environment by never invoking
        // loadPrograms — the map stays empty. The renderer then routes
        // every caller through the legacy iText-default path.
        PdfFontRegistry registry = new PdfFontRegistry();
        PdfFont font = registry.createFont("Arial", false, false);
        assertNull(font);
    }
}
