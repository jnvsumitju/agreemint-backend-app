package com.agreemint.api;

import com.agreemint.ai.AiTemplatePromptBuilder;
import com.agreemint.ai.DeepSeekClient;
import com.agreemint.api.dto.AiOutlineRequest;
import com.agreemint.security.UserPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asks the model to outline the sections of a long, structured document
 * BEFORE generating it. The frontend uses the outline to split the work
 * into sequential generation chunks (typically 2–4 sections per chunk),
 * which dramatically improves output quality on large documents — V4-Flash
 * starts cutting corners (dropping spaces, raw-JSON leaks, overlapping
 * elements) when asked to emit 60K+ tokens in one shot.
 *
 * <p>The response is a small JSON object: {@code {"sections": [{title,
 * summary, estimatedPages}]}}. On any failure (model down, malformed
 * JSON), returns {@code {"sections": []}} so the frontend gracefully
 * falls back to a single-pass generation.
 */
@RestController
@RequestMapping("/api/templates")
public class AiOutlineController {

    private static final Logger log = LoggerFactory.getLogger(AiOutlineController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeepSeekClient deepSeek;
    private final AiTemplatePromptBuilder promptBuilder;

    public AiOutlineController(DeepSeekClient deepSeek, AiTemplatePromptBuilder promptBuilder) {
        this.deepSeek = deepSeek;
        this.promptBuilder = promptBuilder;
    }

    @PostMapping(value = "/{templateId}/ai-outline", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> outline(@PathVariable String templateId,
                                            @AuthenticationPrincipal UserPrincipal principal,
                                            @RequestBody AiOutlineRequest request) {
        String systemPrompt = promptBuilder.buildOutlineSystemPrompt(
                request.currentLayout(), request.variables());
        String userInstruction = request.instruction() == null ? "" : request.instruction();
        if (userInstruction.isBlank()) {
            return ResponseEntity.ok(MAPPER.createObjectNode().putArray("sections").arrayNode());
        }
        try {
            // 6K covers ~2K reasoning + a 15-section outline (~25 tokens
            // per section + structural overhead). V4-Pro reasoning eats
            // most of the smaller budget and leaves no room for output.
            String raw = deepSeek.chatCompletion(systemPrompt, userInstruction, 6144);
            JsonNode parsed = MAPPER.readTree(raw);
            // Accept either {sections: [...]} or a bare [...]
            if (parsed.isArray()) {
                var wrapped = MAPPER.createObjectNode();
                wrapped.set("sections", parsed);
                parsed = wrapped;
            }
            log.info("AI outline for template {} user {} → {} sections",
                    templateId,
                    principal == null ? "?" : principal.userId(),
                    parsed.path("sections").size());
            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            log.warn("AI outline failed for template {}: {} — returning empty",
                    templateId, e.getMessage());
            var fallback = MAPPER.createObjectNode();
            fallback.putArray("sections");
            return ResponseEntity.ok(fallback);
        }
    }
}
