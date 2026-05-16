package com.agreemint.api;

import com.agreemint.ai.AiTemplatePromptBuilder;
import com.agreemint.ai.DeepSeekClient;
import com.agreemint.api.dto.AiClarifyRequest;
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
 * Decides whether an AI generation request needs follow-up clarification
 * before producing a layout. The editor's AI modal calls this first; if
 * the response is {@code {"ready": true}} it streams the generation
 * immediately. If the response contains a {@code questions} array, the
 * modal renders each question and gathers answers, then submits the
 * original instruction concatenated with the answers to the existing
 * {@code /ai-generate} endpoint.
 *
 * <p>The model decides on its own whether the prompt is specific enough
 * — see {@link AiTemplatePromptBuilder#buildClarifierSystemPrompt} for
 * the heuristics it follows. We pass the response straight through to
 * the client without enriching, since the schema is small and the
 * frontend only renders the recognised shape.
 */
@RestController
@RequestMapping("/api/templates")
public class AiClarifyController {

    private static final Logger log = LoggerFactory.getLogger(AiClarifyController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeepSeekClient deepSeek;
    private final AiTemplatePromptBuilder promptBuilder;

    public AiClarifyController(DeepSeekClient deepSeek, AiTemplatePromptBuilder promptBuilder) {
        this.deepSeek = deepSeek;
        this.promptBuilder = promptBuilder;
    }

    @PostMapping(value = "/{templateId}/ai-clarify", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> clarify(@PathVariable String templateId,
                                            @AuthenticationPrincipal UserPrincipal principal,
                                            @RequestBody AiClarifyRequest request) {
        String systemPrompt = promptBuilder.buildClarifierSystemPrompt(
                request.currentLayout(), request.variables(), request.targetElementId());
        String userInstruction = request.instruction() == null ? "" : request.instruction();
        if (userInstruction.isBlank()) {
            // Empty instruction can't produce useful questions — let the
            // generator handle the rejection so we don't double-spend.
            return ResponseEntity.ok(MAPPER.createObjectNode().put("ready", true));
        }
        try {
            // 4K covers ~1.5K reasoning + ~200 token JSON answer with
            // headroom. V4-Pro on reasoning_effort=low still spends
            // 800–1500 tokens thinking; the default 1K cap left zero
            // budget for the actual answer (finish_reason=length).
            String raw = deepSeek.chatCompletion(systemPrompt, userInstruction, 4096);
            JsonNode parsed = MAPPER.readTree(raw);
            // Defensive: the model occasionally returns just the questions
            // array without the wrapper. Coerce to {questions: [...]}.
            if (parsed.isArray()) {
                var wrapped = MAPPER.createObjectNode();
                wrapped.set("questions", parsed);
                parsed = wrapped;
            }
            log.info("AI clarify for template {} user {} → {}",
                    templateId,
                    principal == null ? "?" : principal.userId(),
                    parsed.has("questions") ? "questions x" + parsed.get("questions").size() : "ready");
            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            // On any failure (model down, bad JSON, network error), fall
            // back to "ready" so the user can still generate — the worst
            // case is they don't get clarifying questions, not that they
            // can't generate at all.
            log.warn("AI clarify failed for template {}: {} — falling back to ready",
                    templateId, e.getMessage());
            return ResponseEntity.ok(MAPPER.createObjectNode().put("ready", true));
        }
    }
}
