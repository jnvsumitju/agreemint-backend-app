package com.agreemint.pdf;

import com.agreemint.api.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.Iterator;
import java.util.Set;

/**
 * Structural validation for {@code element.behaviour} (aligned with layout-behaviour.v1.json).
 *
 * <p>In addition to the legacy {@code visibilityRules / colorRules / size / imageSrcExpr} fields,
 * this validator now also understands the unified {@code rules[]} shape introduced with the
 * element behaviour v2 editor. When both are present we accept both — the resolver prefers
 * {@code rules[]} at render time.
 */
public final class LayoutBehaviourValidator {

    /** Known binding targets — kept in sync with the frontend registry. */
    private static final Set<String> BINDING_TARGETS = Set.of(
            "x", "y", "width", "height",
            "strokeWidth", "strokeColor", "lineStyle",
            "fillColor",
            "opacity", "rotation",
            "borderRadius", "borderWidth",
            "shadowX", "shadowY", "shadowBlur", "shadowColor",
            "textColor", "fontSize", "fontFamily", "lineHeight", "textAlign",
            "imageSrc");

    /** Operators accepted by both the legacy leaf-condition and the new tree leaves. */
    private static final Set<String> CONDITION_OPS = Set.of(
            "eq", "neq", "gt", "gte", "lt", "lte", "in", "defined");

    /** Value-mode enum for rule actions of kind {@code set}. */
    private static final Set<String> RULE_VALUE_MODES = Set.of(
            "fixed", "variable", "scaled", "mapping", "expression");

    private LayoutBehaviourValidator() {
    }

    public static void validateLayoutElements(JsonNode layout) {
        JsonNode pages = layout.path("pages");
        if (pages.isArray() && !pages.isEmpty()) {
            for (JsonNode p : pages) {
                validateElementsArray(p.path("elements"));
            }
            return;
        }
        validateElementsArray(layout.path("elements"));
    }

    private static void validateElementsArray(JsonNode elements) {
        if (!elements.isArray()) {
            return;
        }
        for (JsonNode el : elements) {
            validateElementBehaviour(el.path("behaviour"));
        }
    }

    private static void validateElementBehaviour(JsonNode b) {
        if (b == null || b.isNull() || b.isMissingNode()) {
            return;
        }
        if (!b.isObject()) {
            throw new BadRequestException("element.behaviour must be an object");
        }
        Iterator<String> it = b.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            switch (k) {
                case "behaviourVersion",
                        "visibilityDefaultShow",
                        "visibilityRules",
                        "colorRules",
                        "size",
                        "textOverflow",
                        "imageSrcExpr",
                        "table",
                        // v2 unified list; may coexist with the legacy keys above during
                        // migration. Resolver prefers `rules` when non-empty.
                        "rules" -> { /* ok */ }
                default -> throw new BadRequestException("Unknown behaviour key: " + k);
            }
        }
        JsonNode vr = b.path("visibilityRules");
        if (!vr.isMissingNode() && vr.isArray()) {
            for (JsonNode r : vr) {
                requireCondition(r.path("when"));
                if (!r.has("show") || !r.get("show").isBoolean()) {
                    throw new BadRequestException("visibilityRules[].show must be boolean");
                }
            }
        }
        JsonNode cr = b.path("colorRules");
        if (!cr.isMissingNode() && cr.isArray()) {
            for (JsonNode r : cr) {
                requireCondition(r.path("when"));
            }
        }
        JsonNode tr = b.path("table").path("rowRules");
        if (tr.isArray()) {
            for (JsonNode r : tr) {
                requireCondition(r.path("when"));
            }
        }
        JsonNode tc = b.path("table").path("cellRules");
        if (tc.isArray()) {
            for (JsonNode r : tc) {
                requireCondition(r.path("when"));
                if (!r.path("colIndex").isIntegralNumber() && !r.path("colIndex").isInt()) {
                    throw new BadRequestException("table.cellRules[].colIndex must be integer");
                }
            }
        }
        validateRulesArray(b.path("rules"));
    }

    /** Validate the v2 unified {@code rules[]} list. Missing / null is fine. */
    private static void validateRulesArray(JsonNode rules) {
        if (rules == null || rules.isMissingNode() || rules.isNull()) {
            return;
        }
        if (!rules.isArray()) {
            throw new BadRequestException("behaviour.rules must be an array");
        }
        int idx = 0;
        for (JsonNode rule : rules) {
            String prefix = "behaviour.rules[" + idx + "]";
            if (rule == null || !rule.isObject()) {
                throw new BadRequestException(prefix + " must be an object");
            }
            if (!rule.path("id").isTextual() || rule.path("id").asText("").isEmpty()) {
                throw new BadRequestException(prefix + ".id must be a non-empty string");
            }
            JsonNode enabled = rule.get("enabled");
            if (enabled != null && !enabled.isBoolean() && !enabled.isNull()) {
                throw new BadRequestException(prefix + ".enabled must be a boolean if present");
            }
            JsonNode when = rule.get("when");
            if (when != null && !when.isNull() && !when.isMissingNode()) {
                requireTreeCondition(when, prefix + ".when");
            }
            validateRuleAction(rule.path("action"), prefix + ".action");
            idx++;
        }
    }

    private static void validateRuleAction(JsonNode action, String prefix) {
        if (action == null || !action.isObject()) {
            throw new BadRequestException(prefix + " must be an object");
        }
        String kind = action.path("kind").asText("");
        switch (kind) {
            case "hide", "show" -> { /* no extra fields required */ }
            case "set" -> {
                String target = action.path("target").asText("");
                if (!BINDING_TARGETS.contains(target)) {
                    throw new BadRequestException(prefix + ".target is not a known BindingTarget: " + target);
                }
                validateRuleValue(action.path("value"), prefix + ".value");
            }
            default -> throw new BadRequestException(
                    prefix + ".kind must be one of hide|show|set (got '" + kind + "')");
        }
    }

    private static void validateRuleValue(JsonNode value, String prefix) {
        if (value == null || !value.isObject()) {
            throw new BadRequestException(prefix + " must be an object");
        }
        String mode = value.path("mode").asText("");
        if (!RULE_VALUE_MODES.contains(mode)) {
            throw new BadRequestException(
                    prefix + ".mode must be one of fixed|variable|scaled|mapping|expression (got '" + mode + "')");
        }
        switch (mode) {
            case "fixed" -> {
                JsonNode v = value.get("value");
                if (v == null || !(v.isTextual() || v.isNumber())) {
                    throw new BadRequestException(prefix + ".value must be a string or number");
                }
            }
            case "variable" -> {
                if (!value.path("var").isTextual()) {
                    throw new BadRequestException(prefix + ".var must be a string");
                }
            }
            case "scaled" -> {
                if (!value.path("var").isTextual()) {
                    throw new BadRequestException(prefix + ".var must be a string");
                }
                if (!value.path("multiplier").isNumber()) {
                    throw new BadRequestException(prefix + ".multiplier must be a number");
                }
                // min / max optional; type-checked on use.
            }
            case "mapping" -> {
                if (!value.path("var").isTextual()) {
                    throw new BadRequestException(prefix + ".var must be a string");
                }
                JsonNode cases = value.path("cases");
                if (!cases.isArray()) {
                    throw new BadRequestException(prefix + ".cases must be an array");
                }
                int ci = 0;
                for (JsonNode c : cases) {
                    String cp = prefix + ".cases[" + ci + "]";
                    if (!c.isObject()) {
                        throw new BadRequestException(cp + " must be an object");
                    }
                    JsonNode m = c.get("match");
                    JsonNode v = c.get("value");
                    if (m == null || !(m.isTextual() || m.isNumber())) {
                        throw new BadRequestException(cp + ".match must be a string or number");
                    }
                    if (v == null || !(v.isTextual() || v.isNumber())) {
                        throw new BadRequestException(cp + ".value must be a string or number");
                    }
                    ci++;
                }
            }
            case "expression" -> {
                if (!value.path("expression").isTextual()) {
                    throw new BadRequestException(prefix + ".expression must be a string");
                }
            }
            default -> { /* already guarded above */ }
        }
    }

    /**
     * Validate the tree-shaped {@link com.agreemint.pdf.LayoutBehaviourResolver Condition} — a
     * leaf {@code compare} node, or an {@code all} / {@code any} branch with a child list.
     */
    private static void requireTreeCondition(JsonNode node, String prefix) {
        if (node == null || node.isNull() || !node.isObject()) {
            throw new BadRequestException(prefix + " must be an object");
        }
        String kind = node.path("kind").asText("");
        switch (kind) {
            case "compare" -> {
                if (!node.has("left") || !node.has("op")) {
                    throw new BadRequestException(prefix + " (compare) requires left and op");
                }
                String op = node.path("op").asText("");
                if (!CONDITION_OPS.contains(op)) {
                    throw new BadRequestException(prefix + ".op '" + op + "' is not a supported operator");
                }
            }
            case "all", "any" -> {
                JsonNode of = node.path("of");
                if (!of.isArray()) {
                    throw new BadRequestException(prefix + "." + kind + " requires an 'of' array");
                }
                int i = 0;
                for (JsonNode child : of) {
                    requireTreeCondition(child, prefix + ".of[" + i + "]");
                    i++;
                }
            }
            default -> throw new BadRequestException(
                    prefix + ".kind must be one of compare|all|any (got '" + kind + "')");
        }
    }

    private static void requireCondition(JsonNode when) {
        if (when == null || when.isNull() || !when.isObject()) {
            throw new BadRequestException("behaviour condition must be an object");
        }
        if (!when.has("left") || !when.has("op")) {
            throw new BadRequestException("behaviour condition requires left and op");
        }
    }
}
