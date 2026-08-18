package com.agreemint.pdf;

import com.agreemint.config.PixelParityProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canvas and the PDF must agree on what {@code lineHeight} means.
 *
 * <p>CSS {@code line-height: 1.45} makes a line box exactly {@code 1.45 ×
 * font-size}. iText's {@code setMultipliedLeading(1.45)} multiplies the
 * <em>font's own</em> line height instead, which for the three families we
 * ship is 1.451× (Inter) and 1.584× (JetBrains Mono) of the font size. Passing
 * the CSS number straight into {@code setMultipliedLeading} therefore inflated
 * every single line in every PDF by 45–58%, so text that sat inside its box on
 * canvas was clipped by the renderer. It reproduced on hand-authored templates
 * and generated ones alike, because it had nothing to do with the content —
 * a one-character string overflowed exactly as much as a full sentence.
 *
 * <p>The whole 219-test suite passed both before and after the fix, so this is
 * the only thing standing between that bug and a re-regression. Asserted as an
 * exact ratio rather than "no overflow on some fixture", so a future change to
 * font metrics or leading cannot drift past it.
 */
class LeadingParityTest {

    private static PdfFontRegistry registry;
    private static final ObjectMapper mapper = new ObjectMapper();

    /** iText and the browser must land within this many pt of each other. */
    private static final float TOLERANCE = 0.05f;

    @BeforeAll
    static void loadRegistry() {
        registry = new PdfFontRegistry();
        ReflectionTestUtils.invokeMethod(registry, "loadPrograms");
        assertTrue(registry.isFullyLoaded(), "TTFs must be present under classpath:fonts/");
    }

    private PdfRendererService parityRenderer() {
        PixelParityProperties flag = new PixelParityProperties();
        flag.setEnabled(true);
        return new PdfRendererService(
                mapper, new LayoutBehaviourResolver(mapper), registry, flag, "https://crixaa.test");
    }

    private float measure(String family, double fontSize, double lineHeight,
                          double width, String content) {
        ObjectNode el = mapper.createObjectNode();
        el.put("id", "probe");
        el.put("type", "TEXT");
        el.put("width", width);
        el.put("content", content);
        ObjectNode style = el.putObject("style");
        style.put("fontSize", fontSize);
        style.put("fontFamily", family);
        style.put("lineHeight", lineHeight);
        return parityRenderer().measureTextElementHeight(el, JsonNodeFactory.instance.objectNode());
    }

    @Test
    void singleLineHeightEqualsFontSizeTimesLineHeight() {
        // Every family we ship, at the sizes and leading the templates use.
        // Inter and JetBrains Mono have materially different intrinsic line
        // heights (1.451 vs 1.584), which is what made the old bug font-dependent
        // — checking only one family would have hidden half of it.
        record Case(String family, double size, double lineHeight) {}
        Case[] cases = {
                new Case("Inter", 16, 1.45),
                new Case("Inter", 9, 1.4),
                new Case("JetBrains Mono", 8.5, 1.45),
                new Case("JetBrains Mono", 11, 1.2),
                new Case("Source Serif 4", 12, 1.5),
        };
        for (Case c : cases) {
            float measured = measure(c.family(), c.size(), c.lineHeight(), 400, "Wg");
            float expected = (float) (c.size() * c.lineHeight());
            assertEquals(expected, measured, TOLERANCE,
                    () -> String.format("%s %.1fpt/%.2f: PDF line box must match the canvas's",
                            c.family(), c.size(), c.lineHeight()));
        }
    }

    @Test
    void heightIsIndependentOfContentLengthWhenItFitsOneLine() {
        // The tell that this was leading and not wrapping: one character
        // measured exactly as tall as a full string.
        float oneChar = measure("JetBrains Mono", 8.5, 1.45, 400, "X");
        float fullLine = measure("JetBrains Mono", 8.5, 1.45, 400, "INV-2026-0041");
        assertEquals(oneChar, fullLine, TOLERANCE, "both are one line, so both are one line box");
        assertEquals(8.5 * 1.45, oneChar, TOLERANCE);
    }

    @Test
    void wrappedTextIsAnExactMultipleOfTheLineBox() {
        // Narrow box, long content — forces three lines at 10pt in a 60pt column.
        float lineBox = (float) (10 * 1.4);
        float measured = measure("Inter", 10, 1.4, 60,
                "wrapping across several lines to prove leading accumulates linearly");
        float lines = measured / lineBox;
        assertEquals(Math.round(lines), lines, 0.02f,
                () -> "wrapped height " + measured + " must be a whole number of "
                        + lineBox + "pt line boxes, got " + lines);
        assertTrue(lines >= 3, "expected the content to wrap, got " + lines + " line(s)");
    }
}
