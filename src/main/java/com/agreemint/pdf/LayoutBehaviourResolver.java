package com.agreemint.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code element.behaviour} against merge data (global + optional table row), matching
 * frontend {@code layoutBehaviourResolve.ts} + {@code unifiedRules.ts}.
 *
 * <p>Two evaluation paths:
 * <ul>
 *   <li><b>Unified (v2)</b>: when {@code behaviour.rules[]} is non-empty, the new sentence-shaped
 *       pipeline runs. Each rule is {@code (optional when) + (hide|show|set target to value)};
 *       sets are applied last-write-wins, first matching hide/show wins visibility.</li>
 *   <li><b>Legacy</b>: the old {@code visibilityRules} / {@code colorRules} / {@code size} /
 *       {@code imageSrcExpr} fields, evaluated individually — kept wired for templates that
 *       haven't been re-saved yet.</li>
 * </ul>
 */
@Service
public class LayoutBehaviourResolver {

    /**
     * Kept identical to {@code PdfRendererService.VAR_PATTERN} and the console's
     * {@code VAR_PIPE_RE}. Three copies of one grammar is already one too many;
     * they must at least agree. Group 1 is the key, group 2 the pipe chain.
     */
    private static final Pattern VAR_PATTERN =
            Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)((?:\\s*\\|\\s*[^}]+)?)\\s*}}");

    /**
     * Kind of literal each binding target expects — drives coercion in the unified path
     * (number vs string). Kept in sync with the frontend {@code bindingTargets.ts} registry.
     */
    private enum ValueKind { NUMBER, STRING }

    private static final Map<String, ValueKind> TARGET_VALUE_KIND = Map.<String, ValueKind>ofEntries(
            Map.entry("x", ValueKind.NUMBER),
            Map.entry("y", ValueKind.NUMBER),
            Map.entry("width", ValueKind.NUMBER),
            Map.entry("height", ValueKind.NUMBER),
            Map.entry("strokeWidth", ValueKind.NUMBER),
            Map.entry("strokeColor", ValueKind.STRING),
            Map.entry("lineStyle", ValueKind.STRING),
            Map.entry("fillColor", ValueKind.STRING),
            Map.entry("opacity", ValueKind.NUMBER),
            Map.entry("rotation", ValueKind.NUMBER),
            Map.entry("borderRadius", ValueKind.NUMBER),
            Map.entry("borderWidth", ValueKind.NUMBER),
            Map.entry("shadowX", ValueKind.NUMBER),
            Map.entry("shadowY", ValueKind.NUMBER),
            Map.entry("shadowBlur", ValueKind.NUMBER),
            Map.entry("shadowColor", ValueKind.STRING),
            Map.entry("textColor", ValueKind.STRING),
            Map.entry("fontSize", ValueKind.NUMBER),
            Map.entry("fontFamily", ValueKind.STRING),
            Map.entry("lineHeight", ValueKind.NUMBER),
            Map.entry("textAlign", ValueKind.STRING),
            Map.entry("imageSrc", ValueKind.STRING));

    private static final Set<String> TEXT_ALIGN_VALUES = Set.of("left", "center", "right");

    /** Shadow defaults — match {@code DEFAULT_SHADOW} in unifiedRules.ts. */
    private static final double DEFAULT_SHADOW_X = 2d;
    private static final double DEFAULT_SHADOW_Y = 2d;
    private static final double DEFAULT_SHADOW_BLUR = 4d;
    private static final String DEFAULT_SHADOW_COLOR = "rgba(0,0,0,0.25)";

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

        // ── Unified v2 path ────────────────────────────────────────────────
        // When the element was saved by the new editor, `rules` is the single
        // source of truth; legacy fields are dropped on save so we prefer
        // `rules` when non-empty.
        JsonNode rules = b.path("rules");
        if (rules.isArray() && !rules.isEmpty()) {
            boolean defaultShow = b.path("visibilityDefaultShow").asBoolean(true);
            UnifiedResolution ur = evaluateUnifiedRules(rules, defaultShow, globalData, rowContext);
            if (!ur.visible) {
                return new Resolution(false, el);
            }
            ObjectNode out = copyElement(el);
            applyRuleSets(out, ur.sets);
            applyTextOverflow(b.path("textOverflow"), out);
            return new Resolution(true, out);
        }

        // ── Legacy path ────────────────────────────────────────────────────
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
            // Behaviour expressions carry pipes too — a width expression or an
            // image src can format a value the same way body text does.
            VariablePipes.Parsed parsed =
                    VariablePipes.parse(key + (m.group(2) == null ? "" : m.group(2)));
            String val = VariablePipes.apply(
                    lookupText(parsed.key(), globalData, rowContext), parsed.pipes());
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

    // ─── Unified rules (v2) evaluation ─────────────────────────────────────

    /** Internal result of walking a unified rules list. */
    private record UnifiedResolution(boolean visible, java.util.List<RuleSet> sets) {
    }

    /**
     * One resolved {@code set} write, ready to apply to the element. {@code value} is either a
     * {@link Number} or a {@link String} depending on the target's {@link ValueKind}. Sets with a
     * null value are dropped upstream (no-op writes).
     */
    private record RuleSet(String target, Object value) {
    }

    /**
     * Walk the unified rules list top-to-bottom. Visibility semantics: first matching hide /
     * show action wins; if none match, fall back to {@code defaultShow}. Sets semantics: every
     * matching rule contributes a write — last write wins when the same target is set twice.
     */
    private UnifiedResolution evaluateUnifiedRules(
            JsonNode rules, boolean defaultShow, JsonNode globalData, JsonNode rowContext) {
        java.util.List<RuleSet> sets = new java.util.ArrayList<>();
        Boolean visibilityVerdict = null;
        for (JsonNode rule : rules) {
            if (rule == null || !rule.isObject()) {
                continue;
            }
            if (rule.path("enabled").isBoolean() && !rule.path("enabled").asBoolean()) {
                continue;
            }
            JsonNode when = rule.get("when");
            boolean matches = when == null || when.isNull() || when.isMissingNode()
                    ? true
                    : evaluateRuleCondition(when, globalData, rowContext);
            if (!matches) {
                continue;
            }
            JsonNode action = rule.path("action");
            String kind = action.path("kind").asText("");
            switch (kind) {
                case "hide" -> {
                    if (visibilityVerdict == null) {
                        visibilityVerdict = Boolean.FALSE;
                    }
                }
                case "show" -> {
                    if (visibilityVerdict == null) {
                        visibilityVerdict = Boolean.TRUE;
                    }
                }
                case "set" -> {
                    String target = action.path("target").asText("");
                    ValueKind vk = TARGET_VALUE_KIND.get(target);
                    if (vk == null) {
                        continue;
                    }
                    Object v = evaluateRuleValue(action.path("value"), vk, globalData, rowContext);
                    if (v != null) {
                        sets.add(new RuleSet(target, v));
                    }
                }
                default -> { /* unknown kind, skip */ }
            }
        }
        boolean visible = visibilityVerdict != null ? visibilityVerdict : defaultShow;
        return new UnifiedResolution(visible, sets);
    }

    /** Tree-shaped condition: compare leaves (flat) + all/any branches. Empty tree = true. */
    private boolean evaluateRuleCondition(JsonNode cond, JsonNode globalData, JsonNode rowContext) {
        if (cond == null || cond.isNull() || !cond.isObject()) {
            return true;
        }
        String kind = cond.path("kind").asText("");
        switch (kind) {
            case "compare" -> {
                return evalCondition(cond, globalData, rowContext);
            }
            case "all" -> {
                JsonNode of = cond.path("of");
                if (!of.isArray()) {
                    return true;
                }
                for (JsonNode child : of) {
                    if (!evaluateRuleCondition(child, globalData, rowContext)) {
                        return false;
                    }
                }
                return true;
            }
            case "any" -> {
                JsonNode of = cond.path("of");
                if (!of.isArray() || of.isEmpty()) {
                    return false;
                }
                for (JsonNode child : of) {
                    if (evaluateRuleCondition(child, globalData, rowContext)) {
                        return true;
                    }
                }
                return false;
            }
            default -> {
                // Back-compat: a bare leaf without a `kind` tag still works.
                return evalCondition(cond, globalData, rowContext);
            }
        }
    }

    /**
     * Resolve a {@code RuleValue} to a concrete literal. Returns {@code null} when the value
     * can't be coerced to the target's kind — upstream treats null as a no-op write.
     */
    private Object evaluateRuleValue(
            JsonNode rv, ValueKind kind, JsonNode globalData, JsonNode rowContext) {
        if (rv == null || !rv.isObject()) {
            return null;
        }
        String mode = rv.path("mode").asText("");
        return switch (mode) {
            case "fixed" -> coerceKind(rv.get("value"), kind);
            case "variable" -> {
                String var = rv.path("var").asText("");
                JsonNode v = lookupJson(var, globalData, rowContext);
                yield coerceKind(v, kind);
            }
            case "scaled" -> {
                if (kind != ValueKind.NUMBER) {
                    yield null;
                }
                String var = rv.path("var").asText("");
                JsonNode v = lookupJson(var, globalData, rowContext);
                Double base = asNumber(v);
                if (base == null) {
                    yield null;
                }
                double multiplier = rv.path("multiplier").asDouble(1d);
                double out = base * multiplier;
                if (rv.has("min") && rv.get("min").isNumber()) {
                    out = Math.max(out, rv.get("min").asDouble());
                }
                if (rv.has("max") && rv.get("max").isNumber()) {
                    out = Math.min(out, rv.get("max").asDouble());
                }
                yield out;
            }
            case "mapping" -> {
                String var = rv.path("var").asText("");
                JsonNode v = lookupJson(var, globalData, rowContext);
                String key = asComparableString(v);
                JsonNode cases = rv.path("cases");
                if (cases.isArray()) {
                    for (JsonNode c : cases) {
                        if (asComparableString(c.get("match")).equals(key)) {
                            yield coerceKind(c.get("value"), kind);
                        }
                    }
                }
                JsonNode fallback = rv.get("fallback");
                yield fallback == null ? null : coerceKind(fallback, kind);
            }
            case "expression" -> {
                String raw = rv.path("expression").asText("");
                String sub = substitute(raw, globalData, rowContext);
                if (kind == ValueKind.NUMBER) {
                    double parsed = evalSizeExpression(sub, Double.NaN);
                    yield Double.isNaN(parsed) ? null : parsed;
                }
                yield sub;
            }
            default -> null;
        };
    }

    /** Coerce a JsonNode literal into the shape the target expects. */
    private static Object coerceKind(JsonNode n, ValueKind kind) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (kind == ValueKind.NUMBER) {
            Double d = asNumber(n);
            return d;
        }
        // STRING
        if (n.isTextual()) {
            return n.asText();
        }
        if (n.isNumber() || n.isBoolean()) {
            return n.asText();
        }
        return n.toString();
    }

    /** Apply every resolved {@link RuleSet} to the element, mutating {@code out} in place. */
    private void applyRuleSets(ObjectNode out, java.util.List<RuleSet> sets) {
        for (RuleSet s : sets) {
            applyRuleSet(out, s.target(), s.value());
        }
    }

    /**
     * Write one (target, value) onto the element. Routing matches the frontend
     * {@code applyRuleSet} in unifiedRules.ts — top-level fields go on {@code out}, style-ish
     * fields go on {@code out.style}, and shadow targets seed defaults so a partial set still
     * produces a visually consistent shadow.
     */
    private void applyRuleSet(ObjectNode out, String target, Object value) {
        if (value == null) {
            return;
        }
        switch (target) {
            // ── Layout (top-level) ────────────────────────────────────────
            case "x" -> out.put("x", asDouble(value));
            case "y" -> out.put("y", asDouble(value));
            case "width" -> out.put("width", asDouble(value));
            case "height" -> out.put("height", asDouble(value));

            // ── Stroke ────────────────────────────────────────────────────
            case "strokeWidth" -> out.put("strokeWidth", asDouble(value));
            case "strokeColor" -> ensureObject(out, "style").put("color", asString(value));
            case "lineStyle" -> ensureObject(out, "style").put("lineStyle", asString(value));

            // ── Fill ──────────────────────────────────────────────────────
            case "fillColor" -> ensureObject(out, "style").put("backgroundColor", asString(value));

            // ── Visual ────────────────────────────────────────────────────
            case "opacity" -> {
                // Storage is 0..1; rule value expressed in % so divide by 100.
                double pct = asDouble(value);
                double stored = pct / 100d;
                if (stored < 0d) {
                    stored = 0d;
                }
                if (stored > 1d) {
                    stored = 1d;
                }
                ensureObject(out, "style").put("opacity", stored);
            }
            case "rotation" -> ensureObject(out, "style").put("rotation", asDouble(value));

            // ── Border ────────────────────────────────────────────────────
            case "borderRadius" -> ensureObject(out, "style").put("borderRadius", asDouble(value));
            case "borderWidth" -> ensureObject(out, "style").put("borderWidth", asDouble(value));

            // ── Shadow (four targets compose into one object with defaults) ─
            case "shadowX" -> ensureShadow(out).put("offsetX", asDouble(value));
            case "shadowY" -> ensureShadow(out).put("offsetY", asDouble(value));
            case "shadowBlur" -> ensureShadow(out).put("blur", asDouble(value));
            case "shadowColor" -> ensureShadow(out).put("color", asString(value));

            // ── Text ──────────────────────────────────────────────────────
            case "textColor" -> ensureObject(out, "style").put("color", asString(value));
            case "fontSize" -> ensureObject(out, "style").put("fontSize", asDouble(value));
            case "fontFamily" -> ensureObject(out, "style").put("fontFamily", asString(value));
            case "lineHeight" -> ensureObject(out, "style").put("lineHeight", asDouble(value));
            case "textAlign" -> {
                String raw = asString(value);
                if (TEXT_ALIGN_VALUES.contains(raw)) {
                    ensureObject(out, "style").put("align", raw);
                }
            }

            // ── Image ─────────────────────────────────────────────────────
            case "imageSrc" -> {
                if ("IMAGE".equalsIgnoreCase(out.path("type").asText(""))) {
                    out.put("src", asString(value));
                }
            }

            default -> { /* unknown target — drop */ }
        }
    }

    /** Ensure {@code out.style.shadow} exists with the DEFAULT_SHADOW baseline, and return it. */
    private ObjectNode ensureShadow(ObjectNode out) {
        ObjectNode style = ensureObject(out, "style");
        JsonNode existing = style.get("shadow");
        if (existing != null && existing.isObject()) {
            return (ObjectNode) existing;
        }
        ObjectNode shadow = JsonNodeFactory.instance.objectNode();
        shadow.put("offsetX", DEFAULT_SHADOW_X);
        shadow.put("offsetY", DEFAULT_SHADOW_Y);
        shadow.put("blur", DEFAULT_SHADOW_BLUR);
        shadow.put("color", DEFAULT_SHADOW_COLOR);
        style.set("shadow", shadow);
        return shadow;
    }

    private static double asDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return 0d;
            }
        }
        return 0d;
    }

    private static String asString(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
