package com.agreemint.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PdfRendererServiceMultiPageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void pageElementArrays_prefersPagesArray() {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode pages = mapper.createArrayNode();
        ObjectNode p1 = mapper.createObjectNode();
        p1.set("elements", textElementArray("A"));
        ObjectNode p2 = mapper.createObjectNode();
        p2.set("elements", textElementArray("B"));
        pages.add(p1);
        pages.add(p2);
        root.set("pages", pages);
        root.set("elements", textElementArray("ignored"));

        List<JsonNode> out = PdfRendererService.pageElementArraysFromLayout(root);
        assertEquals(2, out.size());
        assertEquals("A", out.get(0).get(0).path("content").asText());
        assertEquals("B", out.get(1).get(0).path("content").asText());
    }

    @Test
    void pageElementArrays_legacyRootElements() {
        ObjectNode root = mapper.createObjectNode();
        root.set("elements", textElementArray("only"));

        List<JsonNode> out = PdfRendererService.pageElementArraysFromLayout(root);
        assertEquals(1, out.size());
        assertEquals("only", out.get(0).get(0).path("content").asText());
    }

    @Test
    void mergedElementsForPdfPage_repeatsPage0HeaderFooterOnLaterPages() {
        ObjectNode header = mapper.createObjectNode();
        header.put("id", "hdr");
        header.put("type", "HEADER");
        header.put("x", 10);
        header.put("y", 10);
        header.put("width", 100);
        header.put("height", 20);
        header.put("content", "H");

        ArrayNode page0 = mapper.createArrayNode();
        page0.add(header);
        page0.add(textElement("t1", "Body1"));

        ArrayNode page1 = mapper.createArrayNode();
        page1.add(textElement("t2", "Body2"));

        List<JsonNode> perPage = List.of(page0, page1);

        List<JsonNode> draw0 = PdfRendererService.mergedElementsForPdfPage(perPage, 0);
        assertEquals(2, draw0.size());
        assertEquals("HEADER", draw0.get(0).path("type").asText());
        assertEquals("Body1", draw0.get(1).path("content").asText());

        List<JsonNode> draw1 = PdfRendererService.mergedElementsForPdfPage(perPage, 1);
        assertEquals(2, draw1.size());
        assertEquals("HEADER", draw1.get(0).path("type").asText());
        assertEquals("Body2", draw1.get(1).path("content").asText());
    }

    @Test
    void mergedElementsForPdfPage_stripsDuplicateHeaderOnPage2() {
        ObjectNode headerP0 = mapper.createObjectNode();
        headerP0.put("id", "hdr");
        headerP0.put("type", "HEADER");
        headerP0.put("x", 0);
        headerP0.put("y", 0);
        headerP0.put("width", 50);
        headerP0.put("height", 10);
        headerP0.put("content", "Main");

        ObjectNode strayHeader = mapper.createObjectNode();
        strayHeader.put("id", "stray");
        strayHeader.put("type", "HEADER");
        strayHeader.put("x", 0);
        strayHeader.put("y", 0);
        strayHeader.put("width", 50);
        strayHeader.put("height", 10);
        strayHeader.put("content", "Ignore");

        ArrayNode page0 = mapper.createArrayNode();
        page0.add(headerP0);
        ArrayNode page1 = mapper.createArrayNode();
        page1.add(strayHeader);
        page1.add(textElement("b", "OnlyBody"));

        List<JsonNode> draw1 = PdfRendererService.mergedElementsForPdfPage(List.of(page0, page1), 1);
        assertEquals(2, draw1.size());
        assertEquals("Main", draw1.get(0).path("content").asText());
        assertEquals("OnlyBody", draw1.get(1).path("content").asText());
    }

    @Test
    void render_twoPages_pdfHasTwoPages() throws IOException {
        ObjectNode pageSpec = mapper.createObjectNode();
        pageSpec.put("size", "A4");
        pageSpec.put("margin", 40);

        ObjectNode root = mapper.createObjectNode();
        root.set("page", pageSpec);
        ArrayNode pages = mapper.createArrayNode();
        ObjectNode docPage1 = mapper.createObjectNode();
        docPage1.put("id", "p1");
        docPage1.put("name", "One");
        docPage1.set("elements", textElementArray("Page1"));
        ObjectNode docPage2 = mapper.createObjectNode();
        docPage2.put("id", "p2");
        docPage2.put("name", "Two");
        docPage2.set("elements", textElementArray("Page2"));
        pages.add(docPage1);
        pages.add(docPage2);
        root.set("pages", pages);
        root.set("elements", textElementArray("mirror"));

        PdfRendererService svc = new PdfRendererService(mapper, new LayoutBehaviourResolver(mapper));
        byte[] pdf = svc.render(root, JsonNodeFactory.instance.objectNode());

        try (PdfReader reader = new PdfReader(new java.io.ByteArrayInputStream(pdf)); PdfDocument doc = new PdfDocument(reader)) {
            assertEquals(2, doc.getNumberOfPages());
        }
    }

    private ArrayNode textElementArray(String content) {
        ArrayNode arr = mapper.createArrayNode();
        arr.add(textElement("t1", content));
        return arr;
    }

    private ObjectNode textElement(String id, String content) {
        ObjectNode el = mapper.createObjectNode();
        el.put("id", id);
        el.put("type", "TEXT");
        el.put("x", 40);
        el.put("y", 40);
        el.put("width", 200);
        el.put("height", 24);
        el.put("content", content);
        return el;
    }
}
