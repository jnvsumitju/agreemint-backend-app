package com.agreemint.api;

import com.agreemint.ai.AiTemplatePromptBuilder;
import com.agreemint.ai.DeepSeekClient;
import com.agreemint.api.dto.AiFixLayoutRequest;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Per-page layout-correction endpoint. The frontend detects geometry / text
 * issues client-side (overlap, overflow, glued text, height drift) and
 * sends them here together with the page JSON. The model returns a
 * minimally-corrected page that the editor swaps in via the standard
 * pending-preview flow.
 *
 * <p>Smaller, faster, cheaper than the full {@code /ai-generate} path —
 * one page in, one page out, no streaming, ~2K output tokens typical.
 */
@RestController
@RequestMapping("/api/templates")
public class AiFixLayoutController {

    private static final Logger log = LoggerFactory.getLogger(AiFixLayoutController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeepSeekClient deepSeek;
    private final AiTemplatePromptBuilder promptBuilder;

    public AiFixLayoutController(DeepSeekClient deepSeek, AiTemplatePromptBuilder promptBuilder) {
        this.deepSeek = deepSeek;
        this.promptBuilder = promptBuilder;
    }

    @PostMapping(value = "/{templateId}/ai-fix-layout", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> fixLayout(@PathVariable String templateId,
                                              @AuthenticationPrincipal UserPrincipal principal,
                                              @RequestBody AiFixLayoutRequest request) {
        if (request.page() == null || request.page().isNull()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page is required");
        }
        if (request.issues() == null || request.issues().isEmpty()) {
            // Nothing to fix — echo the page back unchanged so the caller
            // can no-op without a second round-trip.
            return ResponseEntity.ok(request.page());
        }
        String systemPrompt = promptBuilder.buildFixLayoutSystemPrompt(
                request.pageSpec(), request.variables());
        String userInstruction = buildUserMessage(request);
        try {
            // 16K covers the largest realistic single-page correction
            // (dense legal page = ~3-6K output, plus headroom for the
            // model's thinking budget which V4 consumes from the same
            // cap). Without this the call comes back as empty content.
            String raw = deepSeek.chatCompletion(systemPrompt, userInstruction, 16384);
            JsonNode parsed = MAPPER.readTree(raw);
            log.info("AI fix-layout for template {} user {} → {} issues fixed",
                    templateId,
                    principal == null ? "?" : principal.userId(),
                    request.issues().size());
            return ResponseEntity.ok(parsed);
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            log.error("AI fix-layout failed for template {}: {}", templateId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Fix-layout failed: " + e.getMessage(), e);
        }
    }

    /**
     * Build the user message: the page JSON, then a numbered issue list.
     * Keep it terse — every byte we save is one less the model has to read.
     */
    private String buildUserMessage(AiFixLayoutRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("Page JSON:\n");
        try {
            sb.append(MAPPER.writeValueAsString(request.page())).append("\n\n");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Failed to serialize page JSON: " + e.getMessage(), e);
        }
        sb.append("Detected issues to fix (").append(request.issues().size()).append("):\n");
        int n = 1;
        for (var issue : request.issues()) {
            sb.append(n++).append(". [").append(issue.kind()).append("] element=")
                    .append(issue.elementId());
            if (issue.data() != null && !issue.data().isNull()) {
                try {
                    sb.append(" data=").append(MAPPER.writeValueAsString(issue.data()));
                } catch (Exception ignore) {
                    /* fall through */
                }
            }
            sb.append('\n');
        }
        sb.append("\nReturn the corrected page JSON object now.");
        return sb.toString();
    }
}
