package com.agreemint.billing;

import com.agreemint.config.RazorpayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Thin client for the Razorpay REST API. Uses the JDK HttpClient, matching
 * {@code DeepSeekClient} and {@code WebhookDispatchJob} rather than adding the
 * Razorpay SDK — we need three endpoints, and the SDK pulls its own HTTP stack.
 *
 * <p>Auth is HTTP Basic with {@code key_id:key_secret}. The secret never leaves
 * this class.
 */
@Component
public class RazorpayClient {

    private static final Logger log = LoggerFactory.getLogger(RazorpayClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RazorpayProperties props;
    private final HttpClient http;

    public RazorpayClient(RazorpayProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Create a subscription against a Razorpay Plan. The returned id is handed
     * to Checkout in the browser, where the customer approves the mandate.
     *
     * @param notes free-form metadata echoed back on every webhook — we put our
     *              org id here so an event can be traced to a tenant even if our
     *              own record is somehow missing.
     */
    public JsonNode createSubscription(String planId, int totalCount, Map<String, String> notes) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("plan_id", planId);
        body.put("total_count", totalCount);
        // Razorpay emails its own invoices/receipts; ours would duplicate them.
        body.put("customer_notify", 1);
        ObjectNode notesNode = body.putObject("notes");
        notes.forEach(notesNode::put);

        return post("/subscriptions", body);
    }

    public JsonNode fetchSubscription(String subscriptionId) {
        return get("/subscriptions/" + subscriptionId);
    }

    /**
     * Cancel a subscription.
     *
     * @param atCycleEnd true to let the customer keep access until the period
     *                   they already paid for ends — the humane default.
     */
    public JsonNode cancelSubscription(String subscriptionId, boolean atCycleEnd) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("cancel_at_cycle_end", atCycleEnd ? 1 : 0);
        return post("/subscriptions/" + subscriptionId + "/cancel", body);
    }

    // ── Internals ──

    private String authHeader() {
        String raw = props.getKeyId() + ":" + props.getKeySecret();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private void assertConfigured() {
        if (!props.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Billing is not configured (set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET)");
        }
    }

    private JsonNode post(String path, ObjectNode body) {
        assertConfigured();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", authHeader())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            return send(req, path);
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to build Razorpay request: " + e.getMessage(), e);
        }
    }

    private JsonNode get(String path) {
        assertConfigured();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authHeader())
                .header("Accept", "application/json")
                .GET()
                .build();
        return send(req, path);
    }

    private JsonNode send(HttpRequest req, String path) {
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Razorpay call interrupted", e);
        } catch (Exception e) {
            log.error("Razorpay call to {} failed", path, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not reach Razorpay: " + e.getMessage(), e);
        }

        if (resp.statusCode() >= 400) {
            // Razorpay returns {"error":{"code","description",...}}. Surface the
            // description — it is customer-safe and usually actionable ("plan
            // does not exist", "amount below minimum") — but log the whole body.
            log.error("Razorpay {} returned {}: {}", path, resp.statusCode(), resp.body());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Razorpay error: " + describeError(resp.body(), resp.statusCode()));
        }

        try {
            return MAPPER.readTree(resp.body());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not parse Razorpay response", e);
        }
    }

    private static String describeError(String body, int status) {
        try {
            JsonNode description = MAPPER.readTree(body).path("error").path("description");
            if (description.isTextual() && !description.asText().isBlank()) {
                return description.asText();
            }
        } catch (Exception ignored) {
            // Fall through to the status code.
        }
        return "upstream returned " + status;
    }
}
