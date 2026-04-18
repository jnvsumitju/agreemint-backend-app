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

    // ── Unified rules (v2) ─────────────────────────────────────────────────

    @Test
    void unifiedRules_hideActionMakesInvisible() {
        ObjectNode el = baseTextEl();
        ObjectNode behaviour = mapper.createObjectNode();
        ObjectNode rule = mapper.createObjectNode();
        rule.put("id", "r1");
        ObjectNode when = mapper.createObjectNode();
        when.put("kind", "compare");
        when.put("left", "{{status}}");
        when.put("op", "eq");
        when.put("right", "draft");
        rule.set("when", when);
        rule.set("action", mapper.createObjectNode().put("kind", "hide"));
        behaviour.set("rules", mapper.createArrayNode().add(rule));
        el.set("behaviour", behaviour);

        ObjectNode data = mapper.createObjectNode();
        data.put("status", "draft");

        assertFalse(resolver.resolveElement(el, data, null).visible());
    }

    @Test
    void unifiedRules_setFixedNumberWritesTopLevel() {
        ObjectNode el = baseTextEl();
        el.put("width", 50);
        ObjectNode behaviour = mapper.createObjectNode();
        ObjectNode rule = mapper.createObjectNode();
        rule.put("id", "r1");
        ObjectNode action = mapper.createObjectNode();
        action.put("kind", "set");
        action.put("target", "width");
        action.set("value", mapper.createObjectNode().put("mode", "fixed").put("value", 240));
        rule.set("action", action);
        behaviour.set("rules", mapper.createArrayNode().add(rule));
        el.set("behaviour", behaviour);

        JsonNode out = resolver.resolveElement(el, mapper.createObjectNode(), null).element();
        assertEquals(240d, out.path("width").asDouble(), 0.001);
    }

    @Test
    void unifiedRules_setColorWritesIntoStyle() {
        ObjectNode el = baseTextEl();
        el.set("style", mapper.createObjectNode());
        ObjectNode behaviour = mapper.createObjectNode();
        ObjectNode rule = mapper.createObjectNode();
        rule.put("id", "r1");
        ObjectNode action = mapper.createObjectNode();
        action.put("kind", "set");
        action.put("target", "fillColor");
        action.set("value", mapper.createObjectNode().put("mode", "fixed").put("value", "#ef4444"));
        rule.set("action", action);
        behaviour.set("rules", mapper.createArrayNode().add(rule));
        el.set("behaviour", behaviour);

        JsonNode out = resolver.resolveElement(el, mapper.createObjectNode(), null).element();
        assertEquals("#ef4444", out.path("style").path("backgroundColor").asText());
    }

    @Test
    void unifiedRules_mappingValueFallsBackWhenNoCaseMatches() {
        ObjectNode el = baseTextEl();
        ObjectNode behaviour = mapper.createObjectNode();
        ObjectNode rule = mapper.createObjectNode();
        rule.put("id", "r1");
        ObjectNode action = mapper.createObjectNode();
        action.put("kind", "set");
        action.put("target", "fillColor");
        ObjectNode value = mapper.createObjectNode();
        value.put("mode", "mapping");
        value.put("var", "status");
        value.set("cases", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("match", "paid").put("value", "#10b981")));
        value.put("fallback", "#e5e7eb");
        action.set("value", value);
        rule.set("action", action);
        behaviour.set("rules", mapper.createArrayNode().add(rule));
        el.set("behaviour", behaviour);

        ObjectNode data = mapper.createObjectNode();
        data.put("status", "pending"); // not in cases → falls back
        JsonNode out = resolver.resolveElement(el, data, null).element();
        assertEquals("#e5e7eb", out.path("style").path("backgroundColor").asText());
    }

    @Test
    void unifiedRules_scaledValueRespectsClamp() {
        ObjectNode el = baseTextEl();
        ObjectNode behaviour = mapper.createObjectNode();
        ObjectNode rule = mapper.createObjectNode();
        rule.put("id", "r1");
        ObjectNode action = mapper.createObjectNode();
        action.put("kind", "set");
        action.put("target", "width");
        ObjectNode value = mapper.createObjectNode();
        value.put("mode", "scaled");
        value.put("var", "percent");
        value.put("multiplier", 200);
        value.put("min", 20);
        value.put("max", 200);
        action.set("value", value);
        rule.set("action", action);
        behaviour.set("rules", mapper.createArrayNode().add(rule));
        el.set("behaviour", behaviour);

        // percent=2 → 2*200 = 400, clamped to max=200.
        ObjectNode data = mapper.createObjectNode();
        data.put("percent", 2);
        JsonNode out = resolver.resolveElement(el, data, null).element();
        assertEquals(200d, out.path("width").asDouble(), 0.001);

        // percent=0 → 0, clamped to min=20.
        data.put("percent", 0);
        out = resolver.resolveElement(el, data, null).element();
        assertEquals(20d, out.path("width").asDouble(), 0.001);
    }

    @Test
    void unifiedRules_andConditionMatchesOnlyWhenAllTrue() {
        ObjectNode el = baseTextEl();
        ObjectNode behaviour = mapper.createObjectNode();
        ObjectNode rule = mapper.createObjectNode();
        rule.put("id", "r1");
        rule.set("when", mapper.createObjectNode()
                .put("kind", "all")
                .set("of", mapper.createArrayNode()
                        .add(mapper.createObjectNode()
                                .put("kind", "compare")
                                .put("left", "{{status}}")
                                .put("op", "eq")
                                .put("right", "overdue"))
                        .add(mapper.createObjectNode()
                                .put("kind", "compare")
                                .put("left", "{{rowCount}}")
                                .put("op", "gt")
                                .put("right", 0))));
        rule.set("action", mapper.createObjectNode().put("kind", "hide"));
        behaviour.set("rules", mapper.createArrayNode().add(rule));
        el.set("behaviour", behaviour);

        ObjectNode data = mapper.createObjectNode();
        data.put("status", "overdue");
        data.put("rowCount", 0);
        assertTrue(resolver.resolveElement(el, data, null).visible(), "rowCount=0 breaks the AND");

        data.put("rowCount", 5);
        assertFalse(resolver.resolveElement(el, data, null).visible(), "both legs true → hides");
    }

    @Test
    void unifiedRules_opacityPercentStoredAs0To1() {
        ObjectNode el = baseTextEl();
        ObjectNode behaviour = mapper.createObjectNode();
        ObjectNode rule = mapper.createObjectNode();
        rule.put("id", "r1");
        ObjectNode action = mapper.createObjectNode();
        action.put("kind", "set");
        action.put("target", "opacity");
        action.set("value", mapper.createObjectNode().put("mode", "fixed").put("value", 50));
        rule.set("action", action);
        behaviour.set("rules", mapper.createArrayNode().add(rule));
        el.set("behaviour", behaviour);

        JsonNode out = resolver.resolveElement(el, mapper.createObjectNode(), null).element();
        assertEquals(0.5d, out.path("style").path("opacity").asDouble(), 0.001);
    }

    @Test
    void unifiedRules_disabledRuleIsSkipped() {
        ObjectNode el = baseTextEl();
        el.put("width", 50);
        ObjectNode behaviour = mapper.createObjectNode();
        ObjectNode rule = mapper.createObjectNode();
        rule.put("id", "r1");
        rule.put("enabled", false);
        ObjectNode action = mapper.createObjectNode();
        action.put("kind", "set");
        action.put("target", "width");
        action.set("value", mapper.createObjectNode().put("mode", "fixed").put("value", 999));
        rule.set("action", action);
        behaviour.set("rules", mapper.createArrayNode().add(rule));
        el.set("behaviour", behaviour);

        JsonNode out = resolver.resolveElement(el, mapper.createObjectNode(), null).element();
        assertEquals(50d, out.path("width").asDouble(), 0.001);
    }

    @Test
    void unifiedRules_rulesTakePrecedenceOverLegacyWhenBothPresent() {
        ObjectNode el = baseTextEl();
        ObjectNode behaviour = mapper.createObjectNode();

        // Legacy: hide when flag=0
        ObjectNode when = mapper.createObjectNode();
        when.put("left", "{{flag}}");
        when.put("op", "eq");
        when.put("right", "0");
        ObjectNode legacy = mapper.createObjectNode();
        legacy.set("when", when);
        legacy.put("show", false);
        behaviour.set("visibilityRules", mapper.createArrayNode().add(legacy));

        // Unified: explicit show — this is what should win when both are present.
        ObjectNode rule = mapper.createObjectNode();
        rule.put("id", "r1");
        ObjectNode unifiedWhen = mapper.createObjectNode();
        unifiedWhen.put("kind", "compare");
        unifiedWhen.put("left", "{{flag}}");
        unifiedWhen.put("op", "eq");
        unifiedWhen.put("right", "0");
        rule.set("when", unifiedWhen);
        rule.set("action", mapper.createObjectNode().put("kind", "show"));
        behaviour.set("rules", mapper.createArrayNode().add(rule));

        el.set("behaviour", behaviour);

        ObjectNode data = mapper.createObjectNode();
        data.put("flag", "0");
        assertTrue(resolver.resolveElement(el, data, null).visible());
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
