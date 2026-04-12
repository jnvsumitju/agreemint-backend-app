package com.agreemint.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code element.behaviour} against merge data (global + optional table row), matching
 * frontend {@code layoutBehaviourResolve.ts}.
 */
@Service
public class LayoutBehaviourResolver {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");

    private final ObjectMapper objectMapper;

    public LayoutBehaviourResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record Resolution(boolean visible, JsonNode element) {
    }

    public Resolution resolveElement(JsonNode el, JsonNode globalData, JsonNode rowContext) {
        JsonNode b = el.path("behaviour");
        if (b == null || b.isNull() || b.isMissingNode()) {
            return new Resolution(true, el);
        }
        if (!resolveVisible(b, globalData, rowContext)) {
            return new Resolution(false, el);
        }
        ObjectNode out = copyElement(el);
        applySize(b.path("size"), out, globalData, rowContext);
        applyColorRules(b.path("colorRules"), out, globalData, rowContext);
        applyTextOverflow(b.path("textOverflow"), out);
        applyImageSrc(b, out, globalData, rowContext);
        return new Resolution(true, out);
    }

    public boolean tableRowHidden(JsonNode behaviour, JsonNode row, JsonNode globalData) {
        JsonNode rules = behaviour.path("table").path("rowRules");
        if (!rules.isArray()) {
            return false;
        }
        for (JsonNode r : rules) {
            if (r.path("hide").asBoolean(false) && evalCondition(r.path("when"), globalData, row)) {
                return true;
            }
        }
        return false;
    }

    /** Returns hex/css colors or blank strings if none. */
    public CellStyleDelta tableCellStyle(
            JsonNode behaviour, JsonNode row, JsonNode globalData, int colIndex) {
        JsonNode rules = behaviour.path("table").path("cellRules");
        if (!rules.isArray()) {
            return CellStyleDelta.EMPTY;
        }
        for (JsonNode r : rules) {
            if (r.path("colIndex").asInt(-1) != colIndex) {
                continue;
            }
            if (!evalCondition(r.path("when"), globalData, row)) {
                continue;
            }
            return new CellStyleDelta(
                    r.path("textColor").asText(""),
                    r.path("backgroundColor").asText(""));
        }
        return CellStyleDelta.EMPTY;
    }

    public record CellStyleDelta(String textColor, String backgroundColor) {
        static final CellStyleDelta EMPTY = new CellStyleDelta("", "");
    }

    private ObjectNode copyElement(JsonNode el) {
        try {
            return (ObjectNode) objectMapper.readTree(objectMapper.writeValueAsString(el));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to copy layout element", e);
        }
    }

    private boolean resolveVisible(JsonNode b, JsonNode globalData, JsonNode rowContext) {
        JsonNode rules = b.path("visibilityRules");
        if (!rules.isArray() || rules.isEmpty()) {
            return b.path("visibilityDefaultShow").asBoolean(true);
        }
        for (JsonNode r : rules) {
            if (evalCondition(r.path("when"), globalData, rowContext)) {
                return r.path("show").asBoolean(true);
            }
        }
        return b.path("visibilityDefaultShow").asBoolean(true);
    }

    private void applySize(JsonNode size, ObjectNode out, JsonNode globalData, JsonNode rowContext) {
        if (size == null || size.isNull() || !size.isObject()) {
            return;
        }
        double w = out.path("width").asDouble();
        double h = out.path("height").asDouble();
        if (size.has("widthExpr") && size.get("widthExpr").isTextual()) {
            String sub = substitute(size.get("widthExpr").asText(""), globalData, rowContext);
            w = evalSizeExpression(sub, w);
        }
        if (size.has("heightExpr") && size.get("heightExpr").isTextual()) {
            String sub = substitute(size.get("heightExpr").asText(""), globalData, rowContext);
            h = evalSizeExpression(sub, h);
        }
        double minW = size.path("minWidth").asDouble(1);
        double maxW = size.path("maxWidth").asDouble(100_000);
        double minH = size.path("minHeight").asDouble(1);
        double maxH = size.path("maxHeight").asDouble(100_000);
        out.put("width", clamp(w, minW, maxW));
        out.put("height", clamp(h, minH, maxH));
    }

    private void applyColorRules(JsonNode rules, ObjectNode out, JsonNode globalData, JsonNode rowContext) {
        if (rules == null || !rules.isArray() || rules.isEmpty()) {
            return;
        }
        for (JsonNode r : rules) {
            if (!evalCondition(r.path("when"), globalData, rowContext)) {
                continue;
            }
            ObjectNode style = ensureObject(out, "style");
            if (r.has("strokeColor") && r.get("strokeColor").isTextual()) {
                style.put("color", r.get("strokeColor").asText());
            }
            if (r.has("fillColor") && r.get("fillColor").isTextual()) {
                style.put("backgroundColor", r.get("fillColor").asText());
            }
            out.set("style", style);
            break;
        }
    }

    private void applyTextOverflow(JsonNode to, ObjectNode out) {
        if (to == null || to.isNull() || !to.isObject()) {
            return;
        }
        String mode = to.path("mode").asText("");
        if (mode.isEmpty()) {
            return;
        }
        String type = out.path("type").asText("").toUpperCase();
        if (!type.equals("TEXT") && !type.equals("HEADER") && !type.equals("FOOTER")) {
            return;
        }
        ObjectNode style = ensureObject(out, "style");
        double minFs = to.path("minFontSize").asDouble(8);
        double baseFs = style.path("fontSize").asDouble(12);
        if ("shrinkToFit".equals(mode)) {
            String plain = stripTags(out.path("content").asText(""));
            double ratio = plain.isEmpty()
                    ? 1.0
                    : Math.min(1.0, out.path("width").asDouble(200) / (plain.length() * (baseFs * 0.52)));
            style.put("fontSize", Math.max(minFs, Math.floor(baseFs * ratio)));
            out.set("style", style);
        } else if ("ellipsis".equals(mode) && out.get("content") != null && out.get("content").isTextual()) {
            String t = out.get("content").asText("").trim();
            if (!t.startsWith("{") && t.length() > 4) {
                int maxChars = Math.max(4, (int) Math.floor(out.path("width").asDouble(200) / (baseFs * 0.45)));
                if (t.length() > maxChars) {
                    out.put("content", t.substring(0, maxChars - 1) + "…");
                }
            }
        }
    }

    private static String stripTags(String html) {
        return html.replaceAll("<[^>]+>", "");
    }

    private void applyImageSrc(JsonNode b, ObjectNode out, JsonNode globalData, JsonNode rowContext) {
        if (!b.has("imageSrcExpr") || !b.get("imageSrcExpr").isTextual()) {
            return;
        }
        if (!"IMAGE".equalsIgnoreCase(out.path("type").asText(""))) {
            return;
        }
        String src = substitute(b.get("imageSrcExpr").asText(""), globalData, rowContext).trim();
        if (!src.isEmpty()) {
            out.put("src", src);
        }
    }

    private static ObjectNode ensureObject(ObjectNode parent, String field) {
        JsonNode s = parent.get(field);
        if (s != null && s.isObject()) {
            return (ObjectNode) s;
        }
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        parent.set(field, o);
        return o;
    }

    private boolean evalCondition(JsonNode when, JsonNode globalData, JsonNode rowContext) {
        if (when == null || !when.isObject()) {
            return false;
        }
        JsonNode leftResolved = resolveOperand(when.get("left"), globalData, rowContext);
        String op = when.path("op").asText("");
        if ("defined".equals(op)) {
            return leftResolved != null && !leftResolved.isNull() && !(leftResolved.isTextual() && leftResolved.asText().isEmpty());
        }
        JsonNode rightNode = when.get("right");
        JsonNode rightResolved = rightNode == null ? null : resolveOperand(rightNode, globalData, rowContext);
        return switch (op) {
            case "eq" -> asComparableString(leftResolved).equals(asComparableString(rightResolved));
            case "neq" -> !asComparableString(leftResolved).equals(asComparableString(rightResolved));
            case "gt", "gte", "lt", "lte" -> {
                Double ln = asNumber(leftResolved);
                Double rn = asNumber(rightResolved);
                if (ln == null || rn == null) {
                    yield false;
                }
                yield switch (op) {
                    case "gt" -> ln > rn;
                    case "gte" -> ln >= rn;
                    case "lt" -> ln < rn;
                    default -> ln <= rn;
                };
            }
            case "in" -> {
                String s = asComparableString(rightResolved);
                String left = asComparableString(leftResolved);
                boolean hit = false;
                for (String p : s.split(",")) {
                    if (left.equals(p.trim())) {
                        hit = true;
                        break;
                    }
                }
                yield hit;
            }
            default -> false;
        };
    }

    private JsonNode resolveOperand(JsonNode raw, JsonNode globalData, JsonNode rowContext) {
        if (raw == null || raw.isNull()) {
            return null;
        }
        if (raw.isBoolean() || raw.isNumber()) {
            return raw;
        }
        if (!raw.isTextual()) {
            return raw;
        }
        String t = raw.asText().trim();
        Matcher m = Pattern.compile("^\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}$").matcher(t);
        if (m.matches()) {
            return lookupJson(m.group(1), globalData, rowContext);
        }
        String sub = substitute(t, globalData, rowContext);
        return objectMapper.getNodeFactory().textNode(sub);
    }

    private static String asComparableString(JsonNode n) {
        if (n == null || n.isNull()) {
            return "";
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isNumber()) {
            return n.asText();
        }
        if (n.isBoolean()) {
            return Boolean.toString(n.asBoolean());
        }
        return n.toString();
    }

    private static Double asNumber(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        if (n.isTextual()) {
            try {
                return Double.parseDouble(n.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private JsonNode lookupJson(String path, JsonNode globalData, JsonNode rowContext) {
        JsonNode n = resolveDataPath(globalData, path);
        if ((n == null || n.isMissingNode() || n.isNull()) && rowContext != null) {
            n = resolveDataPath(rowContext, path);
        }
        return n;
    }

    private String substitute(String template, JsonNode globalData, JsonNode rowContext) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher m = VAR_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            String val = lookupText(key, globalData, rowContext);
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String lookupText(String path, JsonNode globalData, JsonNode rowContext) {
        JsonNode n = lookupJson(path, globalData, rowContext);
        if (n == null || n.isNull() || n.isMissingNode()) {
            return "";
        }
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isNumber() || n.isBoolean()) {
            return n.asText();
        }
        return n.toString();
    }

    private static JsonNode resolveDataPath(JsonNode root, String path) {
        if (root == null || path == null || path.isEmpty()) {
            return null;
        }
        String[] parts = path.split("\\.");
        JsonNode cur = root;
        for (String part : parts) {
            if (cur == null || !cur.has(part)) {
                return null;
            }
            cur = cur.get(part);
        }
        return cur;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.min(hi, Math.max(lo, v));
    }

    double evalSizeExpression(String expr, double fallback) {
        String s = expr.replaceAll("\\s+", "");
        if (s.isEmpty()) {
            return fallback;
        }
        try {
            double v = new ExprParser(s).parseExpr();
            return Double.isFinite(v) ? v : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final class ExprParser {
        private final String s;
        private int i = 0;

        ExprParser(String s) {
            this.s = s;
        }

        double parseExpr() {
            double v = parseTerm();
            while (peek() == '+' || peek() == '-') {
                char op = peek();
                i++;
                double r = parseTerm();
                v = op == '+' ? v + r : v - r;
            }
            return v;
        }

        double parseTerm() {
            double v = parseFactor();
            while (peek() == '*' || peek() == '/') {
                char op = peek();
                i++;
                double r = parseFactor();
                v = op == '*' ? v * r : (r == 0 ? v : v / r);
            }
            return v;
        }

        double parseFactor() {
            if (eat('(')) {
                double v = parseExpr();
                eat(')');
                return v;
            }
            if (starts("min(")) {
                i += 4;
                double a = parseExpr();
                eat(',');
                double b = parseExpr();
                eat(')');
                return Math.min(a, b);
            }
            if (starts("max(")) {
                i += 4;
                double a = parseExpr();
                eat(',');
                double b = parseExpr();
                eat(')');
                return Math.max(a, b);
            }
            if (starts("clamp(")) {
                i += 6;
                double x = parseExpr();
                eat(',');
                double lo = parseExpr();
                eat(',');
                double hi = parseExpr();
                eat(')');
                return LayoutBehaviourResolver.clamp(x, lo, hi);
            }
            int start = i;
            if (peek() == '-') {
                i++;
            }
            while (i < s.length() && (Character.isDigit(peek()) || peek() == '.')) {
                i++;
            }
            String chunk = s.substring(start, i);
            try {
                return Double.parseDouble(chunk);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : 0;
        }

        boolean eat(char c) {
            if (peek() == c) {
                i++;
                return true;
            }
            return false;
        }

        boolean starts(String p) {
            return s.regionMatches(i, p, 0, p.length());
        }
    }
}
