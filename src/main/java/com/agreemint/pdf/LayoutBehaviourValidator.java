package com.agreemint.pdf;

import com.agreemint.api.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.Iterator;

/**
 * Structural validation for {@code element.behaviour} (aligned with layout-behaviour.v1.json).
 */
public final class LayoutBehaviourValidator {

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
                        "table" -> { /* ok */ }
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
