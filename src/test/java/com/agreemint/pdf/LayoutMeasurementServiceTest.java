package com.agreemint.pdf;

import com.agreemint.api.dto.MeasureResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-0 contract test. The service itself is stubbed (empty measurements per
 * element) — what's asserted here is the <em>shape</em> of the response and
 * the element-id walk, so the frontend integration can be built against a real
 * contract before phase 1 fills in the measurement pass.
 */
class LayoutMeasurementServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    // Passing null for the ObjectProvider means `getIfAvailable()` yields null,
    // which makes {@link LayoutMeasurementService} return an empty measurement
    // for every element. That's exactly what we want for these contract tests —
    // phase-1 renderer integration is covered by a separate Spring-context test.
    private final LayoutMeasurementService service = new LayoutMeasurementService(null);

    @Test
    void measure_returnsEmptyMeasurementPerElement() {
        ObjectNode layout = mapper.createObjectNode();
        ArrayNode elements = layout.putArray("elements");
        elements.add(textEl("el_a"));
        elements.add(textEl("el_b"));

        MeasureResponse response = service.measure(layout, mapper.createObjectNode(), null);

        assertEquals(2, response.measurements().size());
        assertTrue(response.measurements().containsKey("el_a"));
        assertTrue(response.measurements().containsKey("el_b"));
        MeasureResponse.ElementMeasurement m = response.measurements().get("el_a");
        assertNotNull(m);
        assertEquals(0f, m.measuredHeight());
        assertTrue(m.textLines().isEmpty());
        assertTrue(m.rowHeights().isEmpty());
    }

    @Test
    void measure_walksMultiPageLayoutIntoFlatIdSet() {
        ObjectNode layout = mapper.createObjectNode();
        ArrayNode pages = layout.putArray("pages");
        ObjectNode page1 = pages.addObject();
        page1.putArray("elements").add(textEl("p1_a")).add(textEl("p1_b"));
        ObjectNode page2 = pages.addObject();
        page2.putArray("elements").add(textEl("p2_a"));

        MeasureResponse response = service.measure(layout, mapper.createObjectNode(), null);

        assertEquals(3, response.measurements().size());
        assertTrue(response.measurements().keySet().containsAll(List.of("p1_a", "p1_b", "p2_a")));
    }

    @Test
    void measure_respectsElementIdSubset() {
        ObjectNode layout = mapper.createObjectNode();
        ArrayNode elements = layout.putArray("elements");
        elements.add(textEl("wanted")).add(textEl("skipped"));

        MeasureResponse response = service.measure(layout, mapper.createObjectNode(), List.of("wanted"));

        assertEquals(1, response.measurements().size());
        assertTrue(response.measurements().containsKey("wanted"));
        assertFalse(response.measurements().containsKey("skipped"));
    }

    @Test
    void measure_nullLayoutReturnsEmpty() {
        MeasureResponse response = service.measure(null, null, null);
        assertTrue(response.measurements().isEmpty());
    }

    private ObjectNode textEl(String id) {
        ObjectNode el = mapper.createObjectNode();
        el.put("id", id);
        el.put("type", "TEXT");
        return el;
    }
}
