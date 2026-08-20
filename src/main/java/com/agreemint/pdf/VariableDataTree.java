package com.agreemint.pdf;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the editor's saved variable values into something the renderer can read.
 *
 * <p>These are two genuinely different shapes and the gap between them is not
 * cosmetic. The editor's Variables panel is a flat form: its state is a
 * {@code Record<string, string>}, so a key is the literal text
 * {@code "totals.grand_total"} and a table's rows are a JSON <em>string</em>.
 * That is what gets PUT to {@code /draft} and stored in {@code template_drafts.variables}
 * and {@code template_versions.variables}, because it is also what has to be
 * loaded back into the form.
 *
 * <p>{@link PdfRendererService} wants the opposite: a nested object, because
 * {@code resolveDataPath} splits on {@code .} and walks, returning null the
 * moment a segment is missing; and real JSON arrays, because a table in loop
 * mode does {@code rows = resolved.isArray() ? resolved : emptyArray()}.
 *
 * <p>Hand the stored shape straight to the renderer and it produces a document
 * that is technically valid and entirely empty: every dotted placeholder
 * substitutes to {@code ""} and every data-bound table draws its header and no
 * body. Nothing throws and nothing is logged — the PDF is simply blank where
 * the content should be.
 *
 * <p>The console has always done this conversion client-side before calling
 * generate or preview, which is why documents look right and only the
 * server-rendered thumbnails were wrong. This is a port of three things the
 * console composes, and it takes the layout for the same reason the console
 * does — a table's rows can only be interpreted against the columns the table
 * actually declares:
 * <ul>
 *   <li>{@code variableValuesToDataTree} — {@code src/lib/layoutBehaviourResolve.ts}
 *   <li>{@code stripSystemVariableKeysFromData} — {@code src/lib/systemTemplateVariables.ts}
 *   <li>the table branch of {@code buildPreviewData} — {@code src/stores/previewStore.ts}
 * </ul>
 * Behaviour is meant to match those exactly; if you change one, change the other.
 */
public final class VariableDataTree {

    /**
     * Reject trailing tokens, because {@code JSON.parse} does.
     *
     * <p>Jackson is happy to read {@code [{"a":1}] oops]} as {@code [{"a":1}]}
     * and stop. The console would throw on that and fall back to showing the
     * raw text, so without this the thumbnail would silently render a truncated
     * table that the preview refuses to render at all — the one direction of
     * disagreement nobody would think to check, because the thumbnail looks
     * more correct than the page it is supposed to be a picture of.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Keys the renderer computes for itself.
     *
     * <p>They have to be dropped, not merely ignored. The editor seeds every
     * placeholder it finds with a humanised placeholder value, so a layout
     * containing <code>{{currentDate}}</code> has the literal string
     * {@code "Currentdate"} sitting in its saved variables. The renderer only
     * stamps the real date when the key is <em>absent</em>
     * ({@code if (!base.has(DATA_KEY_CURRENT_DATE))}), so passing the saved
     * value through prints the word "Currentdate" on the thumbnail while the
     * preview — which strips these before sending — prints today's date. The
     * author cannot even see the offending value: the Variables tab hides
     * system keys.
     */
    private static final Set<String> SYSTEM_KEYS = Set.of("totalPages", "pageNumber", "currentDate");

    private VariableDataTree() {}

    /** @deprecated layout-free form; tables cannot be projected without columns. */
    @Deprecated
    public static ObjectNode build(JsonNode flatVariables) {
        return build(null, flatVariables);
    }

    /**
     * @param layoutJson the layout being rendered, used to find TABLE columns
     * @param flatVariables the stored variables object, or null
     * @return a nested object safe to pass as the renderer's {@code data}
     */
    public static ObjectNode build(JsonNode layoutJson, JsonNode flatVariables) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        if (flatVariables == null || !flatVariables.isObject()) return root;

        Map<String, List<String>> tableColumns = tableColumnsByDataKey(layoutJson);

        Iterator<Map.Entry<String, JsonNode>> it = flatVariables.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            if (key == null || key.isEmpty()) continue;
            if (SYSTEM_KEYS.contains(key)) continue;

            List<String> columns = tableColumns.get(key);
            JsonNode value = columns != null
                    ? tableRows(e.getValue(), columns)
                    : coerce(e.getValue());

            if (key.indexOf('.') >= 0) {
                // -1 so a trailing dot keeps its empty segment, matching
                // JavaScript's String.split. Java drops trailing empties by
                // default, which would silently nest a key one level shallower
                // here than the editor did.
                setDeep(root, key.split("\\.", -1), value);
            } else {
                root.set(key, value);
            }
        }
        return root;
    }

    /**
     * Rows for a data-bound table, reduced to the columns it actually draws.
     *
     * <p>The projection is what makes the blank-row filter mean the same thing
     * here as in the console: it filters rows <em>after</em> reducing them to
     * the column keys, so a row carrying content only under keys the table does
     * not render counts as blank. Filtering the raw parsed rows instead would
     * keep such a row and draw it as a line of empty cells, which is precisely
     * what happens when someone renames a column after entering data.
     */
    private static JsonNode tableRows(JsonNode stored, List<String> columns) {
        JsonNode parsed = stored != null && stored.isTextual() ? tryParse(stored.asText()) : stored;
        if (parsed == null) return JsonNodeFactory.instance.arrayNode();

        // TWO shapes reach here, and only one of them used to.
        //
        //  legacy      [{"date":"03 Jul","amount":"45000"}, …]
        //  structured  {"data":[["date","amount"],["03 Jul","45000"], …],
        //               "cellStyle":…, "borderStyle":…}
        //
        // Structured is what the console writes — PropertiesPanel's Loop toggle
        // calls serializeTableVariableData, and every canvas edit re-serialises
        // it. The old guard was `!parsed.isArray() -> empty`, so an object fell
        // straight through it and EVERY data-bound table a customer built
        // rendered with its header and no rows. Nothing threw; the document was
        // simply wrong.
        //
        // Legacy is still what the 50 shipped bundles carry, and what an API
        // caller sends, so both must work.
        List<JsonNode> body = new ArrayList<>();
        boolean positional = false;
        if (parsed.isArray()) {
            parsed.forEach(body::add);
        } else if (parsed.isObject() && parsed.path("data").isArray()) {
            JsonNode grid = parsed.get("data");
            // Row 0 is the header row. Body cells are addressed by POSITION
            // against the layout's declared columns, mirroring the console's
            // own structuredToLegacyRows.
            for (int r = 1; r < grid.size(); r++) body.add(grid.get(r));
            positional = true;
        } else {
            return JsonNodeFactory.instance.arrayNode();
        }

        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (JsonNode item : body) {
            ObjectNode row = JsonNodeFactory.instance.objectNode();
            boolean anyContent = false;
            for (int i = 0; i < columns.size(); i++) {
                String col = columns.get(i);
                JsonNode cell;
                if (positional) {
                    cell = item != null && item.isArray() && i < item.size() ? item.get(i) : null;
                } else {
                    cell = item != null && item.isObject() ? item.get(col) : null;
                }
                String text = cell == null || cell.isNull() ? ""
                        : cell.isTextual() ? cell.asText() : cell.toString();
                row.put(col, text);
                if (!text.trim().isEmpty()) anyContent = true;
            }
            // The table editor deliberately keeps a blank row while the author
            // is filling it in — deleting it under them was a real bug — so a
            // blank row legitimately reaches storage and must not print as an
            // empty line in the finished document.
            if (anyContent) out.add(row);
        }
        return out;
    }

    /**
     * Every TABLE element's dataKey and the column keys it declares.
     *
     * <p>Only tables. A LIST in loop mode reads the same kind of array but has
     * no columns and no blank-row rule in the console, so touching those here
     * would renumber an ordered list whose author left a deliberate gap.
     */
    private static Map<String, List<String>> tableColumnsByDataKey(JsonNode layoutJson) {
        Map<String, List<String>> out = new HashMap<>();
        if (layoutJson == null || !layoutJson.isObject()) return out;

        // Walks exactly what the renderer walks. It used to iterate `pages`
        // only, with no fallback and no band recursion, while
        // PdfRendererService.pageElementArraysFromLayout falls back to a
        // top-level `elements` array and re-dispatches band children.
        //
        // The consequence was quiet rather than loud: a layout stored with
        // top-level elements — which assertValidLayout explicitly permits —
        // rendered correctly but got no column projection, so its blank rows
        // were never filtered and printed as empty lines in the finished
        // document. A TABLE inside a header or footer band had the same problem
        // even when the layout did use pages.
        for (JsonNode elements : elementArrays(layoutJson)) {
            collectTables(elements, out);
        }
        return out;
    }

    /** `pages[].elements` when there are pages, else the root `elements`. */
    private static List<JsonNode> elementArrays(JsonNode layoutJson) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode pages = layoutJson.path("pages");
        if (pages.isArray() && !pages.isEmpty()) {
            for (JsonNode page : pages) {
                JsonNode els = page.path("elements");
                if (els.isArray()) out.add(els);
            }
            return out;
        }
        JsonNode root = layoutJson.path("elements");
        if (root.isArray()) out.add(root);
        return out;
    }

    /** Collect TABLE columns, descending into header/footer bands. */
    private static void collectTables(JsonNode elements, Map<String, List<String>> out) {
        for (JsonNode el : elements) {
            JsonNode band = el.path("bandElements");
            if (band.isArray()) collectTables(band, out);

            if (!"TABLE".equalsIgnoreCase(el.path("type").asText(""))) continue;
            String dataKey = el.path("dataKey").asText("").trim();
            if (dataKey.isEmpty()) continue;

            List<String> keys = new ArrayList<>();
            for (JsonNode col : el.path("columns")) {
                String k = col.path("key").asText("");
                if (!k.isEmpty()) keys.add(k);
            }
            // Matches getTableColumnsForDataKey's fallback for a table that
            // declares no columns.
            out.putIfAbsent(dataKey, keys.isEmpty() ? List.of("value") : keys);
        }
    }

    /**
     * Parse a value that is a JSON document held in a string.
     *
     * <p>Only when it looks like one — trimmed and wrapped in {@code []} or
     * {@code {}}. A parse failure keeps the original text rather than dropping
     * it, so a half-typed value still prints as the author typed it instead of
     * vanishing.
     */
    private static JsonNode coerce(JsonNode value) {
        if (value == null || value.isNull()) return JsonNodeFactory.instance.textNode("");
        if (!value.isTextual()) return value.deepCopy();

        JsonNode parsed = tryParse(value.asText());
        return parsed != null ? parsed : value.deepCopy();
    }

    /** @return the parsed node, or null when it is not JSON-looking or will not parse. */
    private static JsonNode tryParse(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        boolean looksLikeJson =
                (t.startsWith("[") && t.endsWith("]")) || (t.startsWith("{") && t.endsWith("}"));
        if (!looksLikeJson) return null;
        try {
            return MAPPER.readTree(t);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Write {@code value} at a dotted path, creating objects along the way.
     *
     * <p>An intermediate that is not an object gets replaced. That mirrors the
     * console and is the only sane resolution: with both {@code "a"} and
     * {@code "a.b"} present, one of them cannot survive, and the renderer can
     * only walk into an object.
     */
    private static void setDeep(ObjectNode root, String[] parts, JsonNode value) {
        ObjectNode cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode next = cur.get(parts[i]);
            if (next == null || !next.isObject()) {
                next = JsonNodeFactory.instance.objectNode();
                cur.set(parts[i], next);
            }
            cur = (ObjectNode) next;
        }
        cur.set(parts[parts.length - 1], value);
    }
}
