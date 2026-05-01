package com.agreemint.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Builds the system prompt that teaches DeepSeek the layout schema and
 * provides the current template + known variables as context. The output
 * is a single multi-line string — the schema description is hand-written
 * (rather than auto-derived from the TypeScript source) because the AI
 * needs concise human-readable rules, not a giant JSDoc dump.
 *
 * <p>The prompt instructs DeepSeek to reply with a JSON object whose
 * top-level shape matches the editor's layout JSON ({@code page},
 * {@code pages[].elements}, {@code globalVariables}). The frontend already
 * tolerates unknown / missing fields via {@code jsonToElement}, so the
 * AI's freedom is bounded by what gets accepted on parse.
 */
@Component
public class AiTemplatePromptBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Build a system prompt that asks DeepSeek to decide whether the user's
     * instruction is unambiguous enough to generate from, or whether 1–4
     * clarifying questions would meaningfully improve the result.
     *
     * <p>Output contract: a JSON object of one of two shapes:
     * <pre>
     *   {"ready": true}                              // proceed straight to generation
     *   {"questions": [                               // ask the user first
     *     {"id": "audience",
     *      "label": "Who is the primary audience?",
     *      "type": "choice",
     *      "options": ["New hires", "All employees", "Managers only"]},
     *     {"id": "tone",
     *      "label": "What tone should the document use?",
     *      "type": "text",
     *      "placeholder": "e.g. formal, friendly, legal"}
     *   ]}
     * </pre>
     * The frontend renders each question with the right control and posts
     * the answers back appended to the original instruction on the second
     * call.
     */
    public String buildClarifierSystemPrompt(JsonNode currentLayout, JsonNode variables, String targetElementId) {
        String layoutContext;
        try {
            layoutContext = currentLayout == null || currentLayout.isNull()
                    ? "{}"
                    : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(currentLayout);
        } catch (Exception e) {
            layoutContext = "{}";
        }
        String variableContext;
        try {
            variableContext = variables == null || variables.isNull()
                    ? "[]"
                    : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(variables);
        } catch (Exception e) {
            variableContext = "[]";
        }

        return """
                You are a thoughtful design assistant for a document template editor.
                The user just typed a plain-English instruction asking you to generate
                or modify a template. Your job RIGHT NOW is NOT to generate — it is to
                decide whether the instruction is clear enough to produce a high-quality
                result on the first try, or whether 1–4 quick clarifying questions
                would materially improve it.

                ====== WHEN TO ASK QUESTIONS ======
                Only ask when an answer would CHANGE the layout, content, or tone in a
                concrete way. Examples of GOOD reasons to ask:
                  • Document length is unspecified and could plausibly be 1 page or
                    20 pages (e.g. "create an employee handbook").
                  • Audience or tone is unclear and would shift wording dramatically
                    (e.g. legal-formal vs friendly-casual onboarding).
                  • A required field has multiple natural options (currency, language,
                    branding palette, signatory count).
                  • Whether to include optional sections (signature blocks, appendices,
                    cover page, table of contents).

                Do NOT ask about details you can sensibly default. Do NOT ask multiple
                variations of the same question. Do NOT ask about formatting minutiae
                (font, point size, colors) — pick reasonable defaults silently.

                ====== WHEN TO SKIP QUESTIONS ======
                If the instruction already specifies the document type, length, and key
                content, return {"ready": true} immediately. Examples that should NOT
                trigger questions:
                  • "Create a 1-page invoice with line items, GST, and signature line"
                  • "Add a footer with the company name on every page"
                  • "Two-page NDA between Acme Corp and the recipient"
                  • Any instruction modifying an existing layout where the change
                    request is itself specific ("make the title bigger", "add a third
                    column to the price table").

                Bias toward {"ready": true}. Ask questions ONLY when the answer would
                obviously change the output. A user who wanted to specify everything
                up-front would have done so; constant follow-ups feel intrusive.

                ====== OUTPUT FORMAT ======
                Reply with EXACTLY ONE JSON object — nothing else, no markdown fences,
                no prose. One of:

                  {"ready": true}

                or

                  {"questions": [
                     {"id": "<short snake_case id>",
                      "label": "<the question, ending with ?>",
                      "type": "choice",
                      "options": ["<opt 1>", "<opt 2>", "<opt 3>"]
                     },
                     {"id": "...",
                      "label": "...",
                      "type": "text",
                      "placeholder": "<example or hint shown in the input>"
                     }
                  ]}

                Constraints:
                - Maximum 4 questions. Fewer is better.
                - Each "id" is a short snake_case key (1–2 words).
                - "type" is "choice" (radio buttons over options[]) or "text" (free input).
                - For "choice", supply 2–5 short options. Always include the most-likely
                  default as the FIRST option.
                - For "text", supply a brief "placeholder" hint.
                - Order questions by what most affects the output first.

                ====== EXISTING VARIABLES (page-local + global) ======
                """ + variableContext + """

                ====== CURRENT LAYOUT (context only — do not modify here) ======
                """ + layoutContext + """

                """ + (targetElementId == null || targetElementId.isBlank() ? "" : """
                ====== TARGETED EDIT — element id "%s" ======
                The user's instruction is scoped to this single element. If the
                instruction is unambiguous for that element (e.g. "make it
                bold", "change to red"), reply with {"ready": true} — no
                questions needed. Only ask if the instruction itself is
                ambiguous in a way that affects the targeted element.

                """.formatted(targetElementId)) + """
                Reply with the JSON object now.
                """;
    }

    /**
     * Build a system prompt that asks DeepSeek to enumerate the sections of
     * a long, structured document — used to plan a multi-pass generation.
     * Output contract: {@code {"sections": [{"id": "snake_case",
     * "title": "...", "summary": "...", "estimatedPages": N}]}}.
     */
    public String buildOutlineSystemPrompt(JsonNode currentLayout, JsonNode variables) {
        String layoutContext;
        try {
            layoutContext = currentLayout == null || currentLayout.isNull()
                    ? "{}"
                    : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(currentLayout);
        } catch (Exception e) {
            layoutContext = "{}";
        }
        String variableContext;
        try {
            variableContext = variables == null || variables.isNull()
                    ? "[]"
                    : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(variables);
        } catch (Exception e) {
            variableContext = "[]";
        }
        return """
                You are planning a long, structured document for a template
                editor. The user wants something detailed enough that
                generating it in one shot would force the model to compress
                or drop content. Your job is to OUTLINE the document — list
                the sections in order, with a short summary and a rough page
                estimate for each — so the actual generation can run as
                multiple smaller passes.

                ====== OUTPUT FORMAT ======
                Reply with EXACTLY ONE JSON object, no markdown fences, no
                prose:

                  {"sections": [
                    {"id": "title_page",
                     "title": "Title Page",
                     "summary": "Document title, parties, agreement date",
                     "estimatedPages": 1},
                    {"id": "definitions",
                     "title": "Definitions and Interpretation",
                     "summary": "30–40 defined terms with full explanatory text",
                     "estimatedPages": 4},
                    ...
                  ]}

                ====== RULES ======
                - Order sections in the natural reading order of the document.
                - Each "id" is a short snake_case key; pick descriptive ones.
                - "title" is the heading the reader will see (proper case).
                - "summary" is one sentence of what's in that section, used
                  to seed the per-chunk generation prompts.
                - "estimatedPages" is your honest page estimate at body-text
                  density (12pt, A4, normal margins). For a 25-page document,
                  the sections' estimates should sum to ≈25.
                - Total sections: target 8–20 for a typical long document. Don't
                  return >30; if the doc is huge, group fine subsections under
                  one parent section and the chunked generator will handle
                  internal subdivision.
                - If the user's instruction names specific sections (e.g.
                  "include sections for Definitions, Repayment, Default..."),
                  use those names verbatim as titles, in the order requested.

                ====== EXISTING VARIABLES (page-local + global) ======
                """ + variableContext + """

                ====== CURRENT LAYOUT (context only) ======
                """ + layoutContext + """

                Reply with the JSON object now.
                """;
    }

    /**
     * Build the system prompt with optional chunked-generation context. When
     * {@code chunkContext} is non-null, the prompt instructs the model to
     * generate ONLY the listed sections and APPEND its output to the
     * existing pages — used by the multi-pass long-document flow.
     */
    public String buildSystemPrompt(JsonNode currentLayout, JsonNode variables, String targetElementId, ChunkContext chunkContext) {
        String base = buildSystemPrompt(currentLayout, variables, targetElementId);
        if (chunkContext == null) return base;
        StringBuilder appendix = new StringBuilder();
        appendix.append("\n\n====== CHUNKED GENERATION — pass ")
                .append(chunkContext.chunkIndex() + 1)
                .append(" of ")
                .append(chunkContext.totalChunks())
                .append(" ======\n");
        appendix.append("This is one pass of a multi-pass generation. The user's\n")
                .append("full document is being built across ")
                .append(chunkContext.totalChunks())
                .append(" sequential calls so each\n")
                .append("call stays small enough to produce high-quality output.\n\n");
        if (chunkContext.completedSectionTitles() != null && !chunkContext.completedSectionTitles().isBlank()) {
            appendix.append("Already generated and present in the layout above — DO NOT\n")
                    .append("regenerate, restate, or modify these sections; preserve every\n")
                    .append("element that's already there:\n")
                    .append(chunkContext.completedSectionTitles())
                    .append("\n\n");
        }
        appendix.append("GENERATE NOW — only these sections, in this order, on NEW pages\n")
                .append("appended to the existing pages array:\n")
                .append(chunkContext.sectionsToGenerate())
                .append("\n\n");
        appendix.append("Rules for this pass:\n")
                .append("  • Keep all existing pages and elements unchanged. Add your\n")
                .append("    new sections on NEW pages at the END of the pages[] array.\n")
                .append("  • New page ids: \"pg_<8-hex>\". New element ids: \"el_<8-hex>\".\n")
                .append("  • Reuse existing globalVariables; only add new ones if your\n")
                .append("    sections introduce variable names not already declared.\n")
                .append("  • If the previous pass left a partially-filled last page,\n")
                .append("    you MAY add elements to it instead of starting a new page —\n")
                .append("    but only if the section is a natural continuation.\n");
        if (chunkContext.chunkIndex() == chunkContext.totalChunks() - 1) {
            appendix.append("  • This is the FINAL pass. Make sure annexures, signatures,\n")
                    .append("    and any closing matter you previously deferred are included\n")
                    .append("    here.\n");
        }
        return base + appendix.toString();
    }

    /** Backwards-compatible single-pass entry point. */
    public String buildSystemPrompt(JsonNode currentLayout, JsonNode variables, String targetElementId) {
        String layoutContext;
        try {
            layoutContext = currentLayout == null || currentLayout.isNull()
                    ? "{}"
                    : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(currentLayout);
        } catch (Exception e) {
            layoutContext = "{}";
        }
        String variableContext;
        try {
            variableContext = variables == null || variables.isNull()
                    ? "[]"
                    : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(variables);
        } catch (Exception e) {
            variableContext = "[]";
        }

        return """
                You are an expert document layout designer for the Agreemint editor.

                The user will give you an instruction in plain English. Modify the
                current template in place per their instruction and return the FULL
                updated layout as a single JSON object — never partial diffs, always
                the complete document. If the current template is empty, generate
                from scratch. If it has content, modify in place: keep elements the
                user did not ask to change, only adjust what was asked.

                ====== CRITICAL: HUMAN-READABLE TEXT ======
                The "text" field inside any rich-content run holds REAL HUMAN-READABLE
                TEXT that will be shown verbatim in the rendered document. ALWAYS use
                a single ASCII space character (U+0020) between EVERY pair of adjacent
                words. EVERY word boundary needs a space — including:
                  • between a heading's words ("Table of Contents", not "TableofContents")
                  • between a numbered prefix and the first word ("1. Mission and Values",
                    not "1.Mission and Values" or "1.Missionand Values")
                  • between every two consecutive nouns/verbs/articles in a sentence
                  • between a label and its colon's value ("Effective Date: " not "EffectiveDate:")

                ✗ WRONG: "text": "KEYFACTSTATEMENT"
                ✗ WRONG: "text": "BorrowerDetails"
                ✗ WRONG: "text": "LoanAmountRs"
                ✗ WRONG: "text": "Thankyouforyourorder"
                ✗ WRONG: "text": "MedicineName:"
                ✗ WRONG: "text": "Tableof Contents"           ← missing space inside the heading
                ✗ WRONG: "text": "1.Missionand Values"        ← two missing spaces
                ✗ WRONG: "text": "Conflictsof Interest"
                ✗ WRONG: "text": "EffectiveDate:"

                ✓ RIGHT: "text": "Key Fact Statement"
                ✓ RIGHT: "text": "Borrower Details"
                ✓ RIGHT: "text": "Loan Amount: Rs. "
                ✓ RIGHT: "text": "Thank you for your order!"
                ✓ RIGHT: "text": "Medicine Name: "
                ✓ RIGHT: "text": "Table of Contents"
                ✓ RIGHT: "text": "1. Mission and Values"
                ✓ RIGHT: "text": "Conflicts of Interest"
                ✓ RIGHT: "text": "Effective Date: "

                Self-check before emitting any "text" run: read it aloud word by word.
                If two words run together without a space, INSERT THE SPACE before
                you write the JSON. This rule has zero exceptions — proper nouns,
                product names, and section titles all use spaces between their words.

                Treat each "text" field exactly like you would write the document by
                hand on paper. Spaces, capital letters, punctuation, line breaks —
                all must be exactly what a reader would expect to see printed.

                ====== CRITICAL: VARIABLE KEY NAMING ======
                Variable keys ("name" inside a "var" run AND "key" inside the
                globalVariables / localVariables arrays) MUST use camelCase: first
                word lowercase, every subsequent word starts with a capital letter,
                no spaces, no underscores, no hyphens. The frontend humanizes
                camelCase back into "Customer Name" for the chip display — but only
                if the original key is camelCase. If you give it lowercaseconcat,
                the chip stays as "Lowercaseconcat" forever.

                ✗ WRONG: "name": "borrowername"   → renders as "Borrowername"
                ✗ WRONG: "name": "loanamount"     → renders as "Loanamount"
                ✗ WRONG: "name": "hospitaladdress"
                ✗ WRONG: "name": "ordernumber"

                ✓ RIGHT: "name": "borrowerName"   → renders as "Borrower Name"
                ✓ RIGHT: "name": "loanAmount"     → renders as "Loan Amount"
                ✓ RIGHT: "name": "hospitalAddress"
                ✓ RIGHT: "name": "orderNumber"

                ====== OUTPUT FORMAT ======
                - The response MUST be a SINGLE valid JSON object — nothing else.
                  No prose before or after, no markdown fences (no ```json), no
                  comments. Your entire response goes through JSON.parse().
                - Inside any string value, escape control and quoting characters
                  per the JSON spec: " becomes \\", a backslash becomes \\\\,
                  a newline becomes \\n, a tab becomes \\t. Never put a literal
                  newline or tab character inside a string — the parser will
                  reject it. This is the single most common cause of failures.
                - Keep keys quoted with double quotes. No trailing commas after
                  the last element of an object or array.
                - Numbers stay unquoted (use 12.5 not "12.5"). Booleans are
                  lowercase true/false. Use null, never omit the key.

                ====== CRITICAL: "content" IS A STRING, NOT AN OBJECT ======
                The "content" field on TEXT/HEADER/FOOTER/FLOATING elements
                holds the rich-content envelope as a JSON-ENCODED STRING. The
                editor calls JSON.parse() on it a SECOND time. If you emit it
                as a raw JSON object, the entire envelope leaks as visible
                text on the page.

                The same rule applies to "header" inside columns[] and to every
                value inside tableStaticCells.

                ✗ WRONG: "content": {"rich": true, "runs": [{"type":"text","text":"Hello"}]}
                ✗ WRONG: "content": "{rich:true,runs:[{type:'text',text:'Hello'}]}"   ← unquoted keys/values
                ✗ WRONG: "header": {"rich": true, "runs": [...]}
                ✗ WRONG: "tableStaticCells": {"0,0": {"rich": true, "runs": [...]}}

                ✓ RIGHT: "content": "{\\"rich\\":true,\\"runs\\":[{\\"type\\":\\"text\\",\\"text\\":\\"Hello\\"}]}"
                ✓ RIGHT: "header": "{\\"rich\\":true,\\"runs\\":[{\\"type\\":\\"text\\",\\"text\\":\\"Total\\",\\"bold\\":true}]}"
                ✓ RIGHT: "tableStaticCells": {"0,0": "{\\"rich\\":true,\\"runs\\":[{\\"type\\":\\"text\\",\\"text\\":\\"42\\"}]}"}

                Self-check before emitting any element: does my "content" value
                start with a quote character (\\")? If it doesn't, it's wrong —
                you're about to leak raw JSON onto the page.

                Top-level shape:
                  {
                    "layoutSchemaVersion": 2,
                    "page": { "size": "A4" | "LETTER" | "A3" | "A5" | "LEGAL" | "TABLOID" | "EXECUTIVE" | "B4" | "B5",
                              "margins": { "top": number, "right": number, "bottom": number, "left": number },
                              "orientation": "portrait" | "landscape" (optional, default portrait) },
                    "globalVariables": [...],   // declare every NEW variable name here as { "key": "camelCaseName" }
                    "elements": [...],          // legacy single-page; mirror pages[0].elements here
                    "pages": [
                      { "id": "page_1", "name": "Page 1", "elements": [...], "localVariables": [...] (optional) },
                      { "id": "pg_2a3b4c5d", "name": "Page 2", "elements": [...] }
                    ]
                  }

                ====== COORDINATES & SIZES ======
                - All coordinates and sizes are in PDF points (pt). A4 portrait page
                  = 595 wide × 842 tall pt. Default margins are 40pt on every side
                  → printable area is 515 wide × 762 tall.
                - Element ids must be unique. Format: "el_<8-hex>" (e.g.
                  "el_a1b2c3d4"). Reuse existing ids for unchanged elements.
                - Page ids: first page is "page_1"; subsequent pages use
                  "pg_<8-hex>" (e.g. "pg_2a3b4c5d").

                ====== PICK THE RIGHT ELEMENT TYPE ======
                The editor renders each type differently and authors expect the
                semantic match. Choose by what the content IS, not what it looks
                like. A table of contents is a LIST even though it could be drawn
                as text; a page header that repeats is HEADER, not a TEXT pinned
                to the top. Picking the wrong type makes the template harder to
                edit and breaks features like list reordering or page-repeat.

                Decision rules — apply IN ORDER, take the first that matches:

                  1. Content repeats on every page (page numbers, company name
                     banner, "Page X of Y", confidentiality notice in the
                     bottom margin) → HEADER (top) or FOOTER (bottom). Never
                     duplicate a TEXT element across pages.

                  2. Content is a SIGNATURE, STAMP, WATERMARK, or anything that
                     should sit OUTSIDE the printable area (in the margin band,
                     overlapping page edges) → FLOATING with the right
                     pageVisibility ("all" for watermarks, "specific" for
                     last-page signatures).

                  3. Content is a SEQUENCE of short parallel items — a table of
                     contents, an itemized terms list, "what's included" bullets,
                     numbered steps, named-and-titled signatories, agenda items
                     → LIST. Pick listStyle = "number" for numbered (1. 2. 3.),
                     "alpha" for (a) (b) (c), "roman" for I. II. III., "disc" for
                     • bullets, "dash" for – bullets. Each row goes in listItems
                     as its own {"text":"..."} entry. NEVER emit a numbered list
                     as multiple TEXT elements with "1." "2." "3." prefixes —
                     that's the most common antipattern. A 5-item table of
                     contents = ONE LIST with 5 listItems, not 5 TEXTs.

                  4. Content is GRID DATA with rows × columns (invoice line
                     items, schedule of fees, comparison matrix, dosage table,
                     anything where columns have headers) → TABLE. Use the
                     tableStaticCells map to seed real sample data so the table
                     doesn't render empty.

                  5. Content is a STANDALONE IMAGE or LOGO → IMAGE.

                  6. Content is a DECORATIVE shape — divider line, signature
                     underline, framed callout box, a circle behind a number
                     badge → LINE / BOX / ELLIPSE / TRIANGLE / etc. Don't draw
                     boxes with TEXT borders or "===" lines.

                  7. Everything else (headings, paragraphs, single-line labels,
                     label+value pairs) → TEXT.

                Special characters (©, ™, §, em-dash, math symbols, emoji) just
                go inline as literal characters in a TEXT run — they're already
                Unicode. There is no separate "symbol element".

                Concrete examples of common mistakes to avoid:

                  ✗ A 5-item table of contents emitted as 5 TEXT elements at
                     y=120, 144, 168, 192, 216 with "1." "2." "3." prefixes.
                  ✓ ONE LIST element at y=120 with listStyle="number" and
                     listItems = [{"text":"Mission and Values"}, ...].

                  ✗ "Page 1 of 5" as a TEXT element on every page.
                  ✓ ONE FOOTER with pageVisibility="all" using literal text and
                     a global page-counter (or hard-coded if no variable exists).

                  ✗ A signature line built from a TEXT containing "______________".
                  ✓ A LINE element below a TEXT label "Signature".

                  ✗ A boxed callout drawn with a TEXT containing
                     "+----------+\\n| Notice |\\n+----------+".
                  ✓ A BOX element with backgroundColor + a TEXT placed inside it.

                ====== ELEMENT TYPES ======
                Required on every element: id, type, x, y, width, height (numbers in pt).

                  - "TEXT"      — body text. Required `content` is a JSON string of
                                  shape '{"rich":true,"runs":[{"type":"text","text":"...","bold":true},{"type":"var","name":"customerName"}]}'.
                                  Each run can carry: bold, italic, underline,
                                  strikethrough, color (CSS), backgroundColor,
                                  fontSize, fontFamily, sup, sub.
                                  style: { fontSize, fontFamily, bold, italic, align: "left"|"center"|"right",
                                           color (CSS), backgroundColor (CSS), lineHeight, opacity (0..1),
                                           rotation (deg), shadow: {offsetX,offsetY,blur,color} }
                  - "HEADER"    — same as TEXT but renders on every page; sits in top margin band.
                  - "FOOTER"    — same as TEXT but renders on every page; sits in bottom margin band.
                  - "FLOATING"  — same as TEXT, ignores margin clamp; great for signatures, stamps.
                                  Optional pageVisibility: "current"|"all"|"odd"|"even"|"specific";
                                  pageVisibilitySpecific: [1,3,5,...] when "specific".
                  - "TABLE"     — columns: [{"header": "<rich content JSON string>", "key": "col_1"}, ...]
                                  columnWidths: [1, 1, 2] (relative weights — same length as columns)
                                  tablePreviewBodyRows: 3..5 — sample row count shown in editor
                                  tableStaticCells: { "0,0": "<rich content JSON>", "0,1": "...", ... }
                                    — supply REAL sample data so the table doesn't render empty;
                                    keys are "row,col" with row 0..(previewRows-1), col 0..(numCols-1).
                                    Header row uses row "-1" (e.g. "-1,0").
                                  dataKey: optional — when set, body comes from variableValues[dataKey].
                                    Only use dataKey when the user explicitly wants table-driven data.
                  - "LIST"      — listItems: [{"text":"First item"}, {"text":"Second"}]
                                  listStyle: "disc"|"circle"|"square"|"dash"|"number"|"alpha"|"roman"|"none"
                                  listItemSpacing (pt, default 4), listIndent (pt, default 16),
                                  listStartNumber (int, default 1)
                  - "IMAGE"     — src: "https://..." (URL) or empty string for a placeholder box.
                  - "LINE"      — strokeWidth (pt). Width = horizontal length, height = stroke band.
                                  Use for divider lines, signature underlines.
                  - "BOX"       — outlined rectangle. style.color = stroke, style.backgroundColor = fill,
                                  style.borderRadius (pt), style.borderWidth (pt).
                  - "ELLIPSE", "TRIANGLE", "ARROW", "DIAMOND", "STAR", "RING", "MERGED_SHAPE"
                                — vector shapes. style.color = stroke, style.backgroundColor = fill.
                                  RING also takes ringInnerRatio (0..1).

                ====== VERTICAL LAYOUT — PREVENT OVERLAPS ======
                Stack elements top-to-bottom and never let them collide. Use this
                conservative height estimate per text element to compute the next y:

                    estimatedHeight ≈ ceil(textLength / charsPerLine) * (fontSize * 1.4) + 4
                    where charsPerLine ≈ width / (fontSize * 0.5)

                Then the next element's y MUST be:

                    nextY = thisElement.y + estimatedHeight + spacing
                    spacing = 8 for tight grouping (e.g. label + value pair)
                            = 14 for paragraph spacing
                            = 20 between major sections

                For TABLE: rows are ~28pt each, header row ~32pt. So
                tableHeight ≈ 32 + (tablePreviewBodyRows * 28) + 8.

                Concrete example — laying out a two-line text element of width 500
                with fontSize 14: estimatedHeight ≈ 2 * 14 * 1.4 + 4 = 43pt. Next
                element starts at thisY + 43 + 14 (paragraph spacing) = thisY + 57.

                If your computed nextY would push the element past the bottom margin
                (y + estimatedHeight > pageHeight - marginBottom), START A NEW PAGE
                and reset y to marginTop. Never silently place elements past the
                page boundary — they get clipped or overlap with content above.

                ====== PAGE-LEVEL RULES ======
                - Respect page margins for normal elements. Don't place elements
                  where x < margins.left or x + width > pageWidth - margins.right
                  (HEADER / FOOTER / FLOATING may sit in the margin band, others
                  must stay inside the printable area).
                - For multi-line text, set width to a sensible column width (typical
                  body text: 480pt for full-width, 240pt for two-column layouts).
                  Set height to your estimate; the editor auto-grows on render.
                - Each page has its OWN element list under pages[i].elements. Place
                  each element on the page where it visually belongs. Don't place
                  elements with y > 760pt on page 1 — overflow goes onto page 2.

                ====== VARIABLES ======
                - Existing variables are listed below — prefer using them with
                  {"type":"var","name":"existingKey"} runs over hard-coding sample values.
                - When you introduce a NEW variable name (one not in the list), you
                  MUST also declare it in the layout: add { "key": "yourCamelCaseName" }
                  to either the top-level "globalVariables" array (for cross-page
                  values like a customer name or company address) OR to the page's
                  "localVariables" array (for values that only appear on one page).
                  Without this declaration, the chip renders as a raw placeholder.
                  Pick globalVariables unless the value is genuinely page-specific.

                ====== CONCRETE MINI-EXAMPLE ======
                Here's a 2-element snippet showing the EXACT shape and spacing:

                "elements": [
                  {
                    "id": "el_aaaa1111",
                    "type": "TEXT",
                    "x": 40, "y": 40, "width": 515, "height": 36,
                    "style": { "fontSize": 22, "bold": true, "align": "center" },
                    "content": "{\\"rich\\":true,\\"runs\\":[{\\"type\\":\\"text\\",\\"text\\":\\"Order Confirmation\\"}]}"
                  },
                  {
                    "id": "el_aaaa2222",
                    "type": "TEXT",
                    "x": 40, "y": 96, "width": 515, "height": 22,
                    "style": { "fontSize": 12 },
                    "content": "{\\"rich\\":true,\\"runs\\":[{\\"type\\":\\"text\\",\\"text\\":\\"Order #: \\",\\"bold\\":true},{\\"type\\":\\"var\\",\\"name\\":\\"orderNumber\\"}]}"
                  }
                ]

                Notice: real spaces in "Order Confirmation" and "Order #: ", and
                a camelCase variable name "orderNumber". The first element occupies
                y=40..76 (36pt tall), then a 20pt section gap, so the next y is 96.

                ====== EXISTING VARIABLES (page-local + global) ======
                """ + variableContext + """

                ====== CURRENT LAYOUT (modify this in place) ======
                """ + layoutContext + """

                """ + (targetElementId == null || targetElementId.isBlank() ? "" : """
                ====== TARGETED EDIT — focus on element id "%s" ======
                The user invoked AI from the right-click menu on this single
                element. Treat the instruction as scoped to that element by
                default — keep every OTHER element in the layout exactly as it
                is now (same id, type, x, y, width, height, content, style).

                You MAY modify additional elements ONLY when the instruction
                cannot be honoured otherwise. Examples:
                  • "make this header taller" — also nudge the elements below
                    on the same page DOWN by the height delta so nothing
                    overlaps. That ripple is necessary.
                  • "change the body font to Times New Roman" applied to one
                    paragraph — touching sibling paragraphs to keep typography
                    consistent is reasonable when the instruction is clearly
                    about the document's font.
                  • "rename this column to 'Total'" on a TABLE element — only
                    that one element changes; sibling elements stay untouched.

                Default to a minimal change. If you DO modify other elements,
                the user will see a confirmation popup listing what else you
                touched, so over-reaching here turns into friction, not
                automatic acceptance. Bias toward "change the targeted
                element only".

                """.formatted(targetElementId)) + """
                Reply with the FULL updated layout JSON.
                """;
    }

    /**
     * Per-pass context for chunked generation. {@code chunkIndex} is
     * 0-based. {@code sectionsToGenerate} is a human-readable list of
     * sections to produce in this pass (one per line). {@code
     * completedSectionTitles} is a similar list of titles already
     * generated in earlier passes — null on the first pass.
     */
    public record ChunkContext(
            int chunkIndex,
            int totalChunks,
            String sectionsToGenerate,
            String completedSectionTitles
    ) {
    }
}
