package com.agreemint.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Request body for {@code POST /api/templates/{id}/ai-generate}.
 *
 * <p>{@code instruction} is the plain-English description the user typed in
 * the magic-wand modal. {@code currentLayout} is the live editor layout —
 * the AI uses it as context so it can modify in place rather than start
 * from scratch. {@code variables} is the resolved list of variable names
 * the user has already defined (page-local + global) so the AI can place
 * variable chips correctly without inventing new variable names.
 */
public record AiGenerateRequest(
        String instruction,
        JsonNode currentLayout,
        JsonNode variables,
        /**
         * When the user invoked AI from the right-click context menu on a
         * single element (rather than the toolbar's "generate / modify
         * everywhere" entry), this is that element's id. The prompt builder
         * adds focused-edit guidance so the model touches related elements
         * only when strictly needed. Null for the broader generate flow.
         */
        String targetElementId,
        /**
         * Optional context for chunked generation of long, structured
         * documents. When present, the prompt instructs the model to
         * produce ONLY the listed sections and append them to the existing
         * pages — used by the multi-pass long-document flow.
         */
        ChunkContext chunkContext
) {
    /** See {@link com.agreemint.ai.AiTemplatePromptBuilder.ChunkContext}. */
    public record ChunkContext(
            int chunkIndex,
            int totalChunks,
            String sectionsToGenerate,
            String completedSectionTitles
    ) {
    }
}
