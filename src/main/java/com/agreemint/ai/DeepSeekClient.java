package com.agreemint.ai;

import com.agreemint.config.DeepSeekProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Thin client for DeepSeek's OpenAI-compatible chat completions API. Uses
 * Java 11 HttpClient — no Spring WebFlux dependency needed. Streaming is
 * driven line-by-line: each SSE event arrives on its own thread (the JDK
 * HttpClient executor) and the supplied {@code onContentDelta} callback is
 * invoked with the text fragment from {@code choices[0].delta.content}. The
 * caller decides what to do with each fragment (forward as SSE, accumulate,
 * etc.); this client just parses the stream and hands deltas over.
 */
@Component
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeepSeekProperties props;
    private final HttpClient http;

    public DeepSeekClient(DeepSeekProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Backwards-compatible non-streaming chat completion. Defaults to a 4K
     * cap. NB: {@code max_tokens} on V4-Pro is the COMBINED budget for
     * reasoning + output. With {@code reasoning_effort=low} the model
     * still spends 800–1500 tokens thinking before it starts emitting,
     * so a small cap (e.g. 1K) leaves zero room for the answer and the
     * call comes back as empty content with {@code finish_reason=length}.
     * Always size the cap as {@code expected_reasoning + expected_output},
     * not just {@code expected_output}.
     */
    public String chatCompletion(String systemPrompt, String userInstruction) {
        return chatCompletion(systemPrompt, userInstruction, 4096);
    }

    /**
     * Non-streaming chat completion with an explicit output budget. Returns
     * the full {@code choices[0].message.content} string. Throws
     * {@link ResponseStatusException} on the same failure modes as the
     * streaming variant.
     *
     * <p>{@code maxTokens} should be sized to the expected response. If it
     * undershoots, V4's thinking budget consumes the cap and {@code content}
     * comes back blank — observable as "DeepSeek returned empty content"
     * with a {@code finish_reason} of {@code length}. The fix-layout
     * endpoint, for example, needs ≥8K because a corrected page of dense
     * legal prose runs ~3–6K output tokens AFTER the model's thinking.
     */
    public String chatCompletion(String systemPrompt, String userInstruction, int maxTokens) {
        if (!props.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "DeepSeek API key not configured (set DEEPSEEK_API_KEY)");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", props.getModel());
        body.put("stream", false);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.2);
        // V4 thinking mode burns 10–30% of max_tokens on internal reasoning
        // before producing output. For straight JSON-emission tasks (layout
        // generation, fix-layout, clarifier, outline) thinking adds nothing
        // and starves the actual content budget — visible as dropped
        // spaces, truncated JSON, and "empty content" 502s. DeepSeek's
        // accepted values are low / medium / high / max / xhigh; "low" is
        // the smallest budget they expose.
        body.put("reasoning_effort", "low");
        ObjectNode responseFormat = body.putObject("response_format");
        responseFormat.put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        ObjectNode sys = MAPPER.createObjectNode();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        messages.add(sys);
        ObjectNode user = MAPPER.createObjectNode();
        user.put("role", "user");
        user.put("content", userInstruction);
        messages.add(user);

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + "/v1/chat/completions"))
                    // Larger responses can run 60–120s on V4-Flash with
                    // thinking enabled; the conservative 60s cap was timing
                    // out fix-layout calls.
                    .timeout(Duration.ofSeconds(180))
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to build DeepSeek request: " + e.getMessage(), e);
        }

        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("DeepSeek call failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to reach DeepSeek: " + e.getMessage(), e);
        }
        if (resp.statusCode() >= 400) {
            log.error("DeepSeek returned {}: {}", resp.statusCode(), resp.body());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "DeepSeek upstream error " + resp.statusCode());
        }
        try {
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode choice = root.path("choices").path(0);
            JsonNode content = choice.path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                // Log everything we know about the empty response — finish
                // reason, reasoning-content length, usage tokens — so we
                // can tell whether we hit the token cap, the content
                // filter, or something stranger upstream.
                String finishReason = choice.path("finish_reason").asText("?");
                int reasoningLen = choice.path("message").path("reasoning_content").asText("").length();
                JsonNode usage = root.path("usage");
                log.error("DeepSeek empty content: finish_reason={} reasoning_chars={} usage={} body={}",
                        finishReason, reasoningLen, usage,
                        resp.body().length() > 2000 ? resp.body().substring(0, 2000) + "…" : resp.body());
                String hint = "length".equals(finishReason)
                        ? "DeepSeek hit the max_tokens cap before producing output (finish_reason=length). Increase max_tokens for this endpoint."
                        : "DeepSeek returned empty content (finish_reason=" + finishReason + ").";
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, hint);
            }
            return content.asText();
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to parse DeepSeek response: " + e.getMessage(), e);
        }
    }

    /**
     * Stream a chat completion. Blocks the calling thread for the duration of
     * the upstream stream. Each text delta is delivered to {@code onContentDelta}
     * as soon as it arrives. Throws {@link ResponseStatusException} when the
     * key is missing or the upstream call fails — the caller (controller) maps
     * this back to the editor as a 5xx + a user-visible error.
     */
    public void streamChatCompletion(String systemPrompt,
                                     String userInstruction,
                                     Consumer<String> onContentDelta) {
        if (!props.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "DeepSeek API key not configured (set DEEPSEEK_API_KEY)");
        }

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", props.getModel());
        body.put("stream", true);
        // 32K covers a 25–30 page heavily-styled handbook comfortably.
        // V4's hard ceiling is 384K; we don't ask for that because
        // generation latency scales with output size, and most templates
        // need <8K. Cost is per *generated* token, so a high cap doesn't
        // bill more for short responses. Lower temperature so the model
        // sticks to the schema rather than improvising free-form prose.
        body.put("max_tokens", 32768);
        body.put("temperature", 0.2);
        // Disable thinking budget — we're doing structured JSON emission,
        // not problem-solving. Thinking tokens compete with content tokens
        // and produce dropped spaces / glued words on long outputs.
        body.put("reasoning_effort", "low");
        // Force JSON-only output — without this DeepSeek tends to wrap the
        // layout in markdown fences (```json ... ```) which we'd have to
        // strip client-side.
        ObjectNode responseFormat = body.putObject("response_format");
        responseFormat.put("type", "json_object");
        ArrayNode messages = body.putArray("messages");
        ObjectNode sys = MAPPER.createObjectNode();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        messages.add(sys);
        ObjectNode user = MAPPER.createObjectNode();
        user.put("role", "user");
        user.put("content", userInstruction);
        messages.add(user);

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + "/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to build DeepSeek request: " + e.getMessage(), e);
        }

        HttpResponse<java.io.InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            log.error("DeepSeek call failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to reach DeepSeek: " + e.getMessage(), e);
        }

        if (resp.statusCode() >= 400) {
            String snippet;
            try (var s = resp.body()) {
                snippet = new String(s.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception readErr) {
                snippet = "<read failed: " + readErr.getMessage() + ">";
            }
            log.error("DeepSeek returned {}: {}", resp.statusCode(), snippet);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "DeepSeek upstream error " + resp.statusCode());
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) continue;
                if ("[DONE]".equals(payload)) break;
                JsonNode evt;
                try {
                    evt = MAPPER.readTree(payload);
                } catch (Exception parseErr) {
                    // True parse failure — a malformed event mid-stream
                    // shouldn't kill the whole generation. Usually a stray
                    // ping line or upstream burp.
                    log.warn("Skipping unparseable DeepSeek SSE chunk: {}", payload);
                    continue;
                }
                JsonNode delta = evt.path("choices").path(0).path("delta").path("content");
                if (!delta.isTextual()) continue;
                String chunk = delta.asText();
                if (chunk.isEmpty()) continue;
                try {
                    onContentDelta.accept(chunk);
                } catch (RuntimeException callbackErr) {
                    // The callback writes to the SSE emitter; if it throws
                    // (client disconnect, async-timeout) the request is over
                    // and continuing to read from DeepSeek is wasted work.
                    // Log once, abort the loop, propagate so the caller can
                    // close the upstream cleanly.
                    log.info("DeepSeek stream aborted — client gone ({})", callbackErr.getMessage());
                    throw callbackErr;
                }
            }
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "DeepSeek stream interrupted: " + e.getMessage(), e);
        }
    }
}
