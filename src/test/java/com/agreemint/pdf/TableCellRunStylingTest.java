package com.agreemint.pdf;

import com.agreemint.config.PixelParityProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-run styling inside a table cell survives into the PDF.
 *
 * <p>This is the guarantee behind the formatting controls the editor offers for
 * a selection inside a cell. The canvas is explicitly not pixel-identical to the
 * PDF, so a control whose mark the renderer ignored would colour text on screen
 * and print it black — the failure mode this codebase treats as a real defect
 * rather than a rough edge, because nobody finds it until a customer has the
 * document in hand.
 *
 * <p>Asserted against the fill-colour operators in the page content stream.
 * "The bytes changed when I set a colour" would also pass for a renderer that
 * wrote the colour into the wrong operator or applied it to the whole table, so
 * each test names the exact colours that must and must not appear.
 *
 * <p>Only marks the renderer actually honours are covered, and that set is what
 * decides which controls the toolbar offers. Per-run font family is absent on
 * purpose: {@code resolveParityFont} reads {@code fontFamily} off the ELEMENT
 * style and never off a run, so a per-selection family picker would look right
 * on the canvas and print in the wrong face.
 */
class TableCellRunStylingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static PdfFontRegistry registry;

    /** Non-stroking (fill) colour operator: {@code r g b rg}. */
    private static final Pattern FILL_RGB = Pattern.compile(
            "([0-9]*\\.?[0-9]+)\\s+([0-9]*\\.?[0-9]+)\\s+([0-9]*\\.?[0-9]+)\\s+rg");

    private static final int ELEMENT_COLOUR = 0x111827;
    private static final int RUN_COLOUR = 0xDC2626;

    @BeforeAll
    static void loadFonts() {
        registry = new PdfFontRegistry();
        ReflectionTestUtils.invokeMethod(registry, "loadPrograms");
    }

    private static PdfRendererService renderer() {
        PixelParityProperties flag = new PixelParityProperties();
        flag.setEnabled(true);
        return new PdfRendererService(
                MAPPER, new LayoutBehaviourResolver(MAPPER), registry, flag, "https://crixaa.test");
    }

    /** Exactly the shape the canvas editor serialises into tableStaticCells. */
    private static String richCell(String text, String colorOrNull, boolean bold) {
        ObjectNode run = MAPPER.createObjectNode();
        run.put("type", "text").put("text", text);
        if (colorOrNull != null) run.put("color", colorOrNull);
        if (bold) run.put("bold", true);

        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("rich", true);
        doc.putArray("runs").add(run);
        return doc.toString();
    }

    /** One page, one loop-off table, two static cells keyed {@code "row,col"}. */
    private static JsonNode layoutWithCells(String cell00, String cell01) {
        ObjectNode table = MAPPER.createObjectNode();
        table.put("id", "t1").put("type", "TABLE")
                .put("x", 40).put("y", 100).put("width", 500).put("height", 120)
                .put("tablePreviewBodyRows", 1);
        table.putObject("style")
                .put("fontSize", 11).put("fontFamily", "Inter").put("color", "#111827");
        var cols = table.putArray("columns");
        cols.addObject().put("header", "Left").put("key", "left");
        cols.addObject().put("header", "Right").put("key", "right");
        ObjectNode statics = table.putObject("tableStaticCells");
        statics.put("0,0", cell00);
        statics.put("0,1", cell01);

        ObjectNode layout = MAPPER.createObjectNode();
        layout.putObject("page").put("size", "A4");
        layout.putArray("pages").addObject().putArray("elements").add(table);
        return layout;
    }

    /** Every distinct fill colour the page sets, as 0xRRGGBB. */
    private static Set<Integer> fillColours(byte[] pdf) throws Exception {
        String content;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            content = new String(doc.getPage(0).getContents().readAllBytes(),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        Set<Integer> out = new LinkedHashSet<>();
        Matcher m = FILL_RGB.matcher(content);
        while (m.find()) {
            int r = Math.round(Float.parseFloat(m.group(1)) * 255f);
            int g = Math.round(Float.parseFloat(m.group(2)) * 255f);
            int b = Math.round(Float.parseFloat(m.group(3)) * 255f);
            out.add((r << 16) | (g << 8) | b);
        }
        return out;
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void aColouredRunInACellPrintsInThatColour() throws Exception {
        byte[] pdf = renderer().render(
                layoutWithCells(richCell("Zulu", null, false), richCell("Quokka", "#DC2626", false)),
                MAPPER.createObjectNode());

        assertTrue(textOf(pdf).contains("Quokka"), "the cell's text must actually be drawn");
        assertTrue(fillColours(pdf).contains(RUN_COLOUR),
                "a run's colour must reach the PDF — the toolbar swatch is pointless otherwise; got "
                        + fillColours(pdf));
    }

    @Test
    void theColourAppliesToThatRunOnlyAndNotTheWholeTable() throws Exception {
        // The failure that would be easy to ship and hard to notice: applying
        // the mark at paragraph or element level, so colouring one word
        // repaints every cell. If that happened the element colour would have
        // been replaced and would no longer appear at all.
        byte[] pdf = renderer().render(
                layoutWithCells(richCell("Zulu", null, false), richCell("Quokka", "#DC2626", false)),
                MAPPER.createObjectNode());

        Set<Integer> colours = fillColours(pdf);
        assertTrue(colours.contains(RUN_COLOUR), "the styled run keeps its own colour");
        assertTrue(colours.contains(ELEMENT_COLOUR),
                "the untouched cell must still print in the element colour; got " + colours);
    }

    @Test
    void anUncolouredCellNeverPicksUpTheOtherCellsColour() throws Exception {
        // The negative half: with no run colour anywhere, the run colour must be
        // absent. Without this, a test that merely looks for two colours would
        // pass on a renderer that painted something red for an unrelated reason.
        byte[] pdf = renderer().render(
                layoutWithCells(richCell("Zulu", null, false), richCell("Quokka", null, false)),
                MAPPER.createObjectNode());

        Set<Integer> colours = fillColours(pdf);
        assertFalse(colours.contains(RUN_COLOUR), "nothing set red, so nothing may print red");
        assertTrue(colours.contains(ELEMENT_COLOUR), "cells fall back to the element colour");
    }

    @Test
    void aHighlightedRunAddsItsBackgroundColour() throws Exception {
        // Highlight had no coverage anywhere in the backend suite before this.
        ObjectNode run = MAPPER.createObjectNode();
        run.put("type", "text").put("text", "Quokka").put("highlightColor", "#FDE047");
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("rich", true);
        doc.putArray("runs").add(run);

        byte[] pdf = renderer().render(
                layoutWithCells(richCell("Zulu", null, false), doc.toString()),
                MAPPER.createObjectNode());

        assertTrue(fillColours(pdf).contains(0xFDE047),
                "a highlight mark must paint its background; got " + fillColours(pdf));
    }

    @Test
    void boldOnARunChangesTheOutput() throws Exception {
        // Coarser than the colour assertions by necessity — weight is a font
        // resource, not a value that reads back out of the graphics state.
        // Requiring the output to differ at least proves the mark is not being
        // dropped on the floor.
        byte[] plain = renderer().render(
                layoutWithCells(richCell("Zulu", null, false), richCell("Quokka", null, false)),
                MAPPER.createObjectNode());
        byte[] bold = renderer().render(
                layoutWithCells(richCell("Zulu", null, false), richCell("Quokka", null, true)),
                MAPPER.createObjectNode());

        assertNotEquals(plain.length, bold.length,
                "a bold run must produce different output from a plain one");
    }
}
