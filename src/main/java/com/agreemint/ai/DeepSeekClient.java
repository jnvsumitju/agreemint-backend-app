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
     * Non-streaming chat completion. Used by the clarifier endpoint where the
     * response is a tiny JSON object (questions list or {"ready":true}) and
     * we don't want to spool an SSE pipe for it. Returns the full
     * {@code choices[0].message.content} string. Throws
     * {@link ResponseStatusException} on the same failure modes as the
     * streaming variant.
     */
    public String chatCompletion(String systemPrompt, String userInstruction) {
        if (!props.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "DeepSeek API key not configured (set DEEPSEEK_API_KEY)");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", props.getModel());
        body.put("stream", false);
        // Clarifier output is small (<2K). Cap conservatively.
        body.put("max_tokens", 1024);
        body.put("temperature", 0.2);
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
                    .timeout(Duration.ofSeconds(60))
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
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "DeepSeek returned empty content");
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
                try {
                    JsonNode evt = MAPPER.readTree(payload);
                    JsonNode delta = evt.path("choices").path(0).path("delta").path("content");
                    if (delta.isTextual()) {
                        String chunk = delta.asText();
                        if (!chunk.isEmpty()) onContentDelta.accept(chunk);
                    }
                } catch (Exception parseErr) {
                    // A malformed event mid-stream shouldn't kill the whole
                    // generation — log + skip. Most often this is a stray
                    // ping line or an upstream burp.
                    log.warn("Skipping malformed DeepSeek SSE chunk: {}", payload);
                }
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "DeepSeek stream interrupted: " + e.getMessage(), e);
        }
    }
}
