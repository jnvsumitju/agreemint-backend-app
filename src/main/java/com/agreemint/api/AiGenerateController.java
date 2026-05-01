package com.agreemint.api;

import com.agreemint.ai.AiTemplatePromptBuilder;
import com.agreemint.ai.DeepSeekClient;
import com.agreemint.api.dto.AiGenerateRequest;
import com.agreemint.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * AI-driven template generation. The editor's magic-wand modal POSTs an
 * instruction here; this endpoint proxies to DeepSeek and streams text
 * deltas back as SSE so the editor can show generation progress and apply
 * the result once the stream completes.
 *
 * <p>Two SSE event names are emitted:
 * <ul>
 *   <li>{@code delta} — payload is the raw text chunk from DeepSeek's
 *     {@code choices[0].delta.content}. The frontend accumulates these.</li>
 *   <li>{@code done} — empty payload sent at end-of-stream so the client
 *     knows it's safe to parse the accumulated text as JSON.</li>
 *   <li>{@code error} — payload is a short user-visible message; the
 *     frontend shows it in a toast and reverts to the live layout.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/templates")
public class AiGenerateController {

    private static final Logger log = LoggerFactory.getLogger(AiGenerateController.class);

    private final DeepSeekClient deepSeek;
    private final AiTemplatePromptBuilder promptBuilder;

    public AiGenerateController(DeepSeekClient deepSeek, AiTemplatePromptBuilder promptBuilder) {
        this.deepSeek = deepSeek;
        this.promptBuilder = promptBuilder;
    }

    @PostMapping(value = "/{templateId}/ai-generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generate(@PathVariable String templateId,
                               @AuthenticationPrincipal UserPrincipal principal,
                               @RequestBody AiGenerateRequest request) {
        // 10-minute SSE timeout. A 25-page handbook on V4-Flash takes
        // 2-4 minutes; the legacy 180s cap was choking long-doc requests
        // before the stream finished even though DeepSeek itself was fine.
        SseEmitter emitter = new SseEmitter(600_000L);

        Thread worker = new Thread(() -> {
            try {
                AiTemplatePromptBuilder.ChunkContext chunk = null;
                if (request.chunkContext() != null) {
                    var c = request.chunkContext();
                    chunk = new AiTemplatePromptBuilder.ChunkContext(
                            c.chunkIndex(), c.totalChunks(),
                            c.sectionsToGenerate(), c.completedSectionTitles());
                }
                String systemPrompt = promptBuilder.buildSystemPrompt(
                        request.currentLayout(), request.variables(), request.targetElementId(), chunk);
                String userInstruction = request.instruction() == null ? "" : request.instruction();

                deepSeek.streamChatCompletion(systemPrompt, userInstruction, delta -> {
                    try {
                        emitter.send(SseEmitter.event().name("delta").data(delta));
                    } catch (IOException e) {
                        // Client disconnected — bail out of the stream loop by
                        // throwing; the outer try/catch closes the emitter.
                        throw new RuntimeException("client disconnected", e);
                    }
                });
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
                log.info("AI generate completed for template {} user {}",
                        templateId, principal == null ? "?" : principal.userId());
            } catch (Exception e) {
                log.error("AI generate failed for template {}: {}", templateId, e.getMessage());
                try {
                    emitter.send(SseEmitter.event().name("error").data(safeMessage(e)));
                } catch (IOException ignore) {
                    // Client already gone.
                }
                emitter.complete();
            }
        }, "ai-generate-" + templateId);
        worker.setDaemon(true);
        worker.start();

        return emitter;
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isBlank()) m = t.getClass().getSimpleName();
        // Don't leak upstream secrets / API keys — never expected here, but
        // cheap to defend against.
        return m.replace("Bearer ", "Bearer ***");
    }
}
