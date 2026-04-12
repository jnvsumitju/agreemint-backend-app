package com.agreemint.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutBehaviourResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final LayoutBehaviourResolver resolver = new LayoutBehaviourResolver(mapper);

    @Test
    void visibility_hidesWhenRuleMatches() throws Exception {
        ObjectNode el = baseTextEl();
        ObjectNode behaviour = mapper.createObjectNode();
        ObjectNode when = mapper.createObjectNode();
        when.put("left", "{{flag}}");
        when.put("op", "eq");
        when.put("right", "0");
        ObjectNode rule = mapper.createObjectNode();
        rule.set("when", when);
        rule.put("show", false);
        behaviour.set("visibilityRules", mapper.createArrayNode().add(rule));
        behaviour.put("visibilityDefaultShow", true);
        el.set("behaviour", behaviour);

        ObjectNode data = mapper.createObjectNode();
        data.put("flag", "0");

        LayoutBehaviourResolver.Resolution r = resolver.resolveElement(el, data, null);
        assertFalse(r.visible());
    }

    @Test
    void size_widthExprUsesData() throws Exception {
        ObjectNode el = baseTextEl();
        el.put("width", 100);
        ObjectNode behaviour = mapper.createObjectNode();
        ObjectNode size = mapper.createObjectNode();
        size.put("widthExpr", "{{w}}");
        size.put("minWidth", 10);
        size.put("maxWidth", 500);
        behaviour.set("size", size);
        el.set("behaviour", behaviour);

        ObjectNode data = mapper.createObjectNode();
        data.put("w", "240");

        JsonNode out = resolver.resolveElement(el, data, null).element();
        assertEquals(240f, (float) out.path("width").asDouble(), 0.01f);
    }

    @Test
    void tableRowHidden_respectsRowRule() {
        ObjectNode behaviour = mapper.createObjectNode();
        ObjectNode table = mapper.createObjectNode();
        ObjectNode when = mapper.createObjectNode();
        when.put("left", "{{kind}}");
        when.put("op", "eq");
        when.put("right", "fee");
        ObjectNode rowRule = mapper.createObjectNode();
        rowRule.set("when", when);
        rowRule.put("hide", true);
        table.set("rowRules", mapper.createArrayNode().add(rowRule));
        behaviour.set("table", table);

        ObjectNode row = mapper.createObjectNode();
        row.put("kind", "fee");

        ObjectNode data = mapper.createObjectNode();
        assertTrue(resolver.tableRowHidden(behaviour, row, data));
    }

    private ObjectNode baseTextEl() {
        ObjectNode el = mapper.createObjectNode();
        el.put("id", "t1");
        el.put("type", "TEXT");
        el.put("x", 10);
        el.put("y", 10);
        el.put("width", 100);
        el.put("height", 20);
        el.put("content", "Hi");
        return el;
    }
}
