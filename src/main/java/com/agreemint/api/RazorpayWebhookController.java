package com.agreemint.api;

import com.agreemint.billing.BillingService;
import com.agreemint.billing.RazorpaySignatureVerifier;
import com.agreemint.config.RazorpayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Razorpay webhook receiver.
 *
 * <p>Unauthenticated by necessity — Razorpay cannot hold our credentials — so
 * the HMAC signature is the <em>only</em> thing standing between this endpoint
 * and an attacker granting themselves a paid plan. Two rules follow:
 *
 * <ol>
 *   <li>The body is taken as a raw {@code String}, not a bound object. Jackson
 *       binding and re-serialising would change the bytes and break the HMAC.</li>
 *   <li>Nothing is parsed or acted on before the signature check passes.</li>
 * </ol>
 *
 * <p>Always answers 200 once a delivery has been accepted, including for events
 * we ignore — a non-2xx makes Razorpay retry, and retrying an event we simply
 * do not care about achieves nothing.
 */
@Tag(name = "Billing", description = "Razorpay webhook receiver")
@RestController
@RequestMapping("/api/webhooks/razorpay")
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BillingService billing;
    private final RazorpaySignatureVerifier verifier;
    private final RazorpayProperties props;

    public RazorpayWebhookController(BillingService billing,
                                      RazorpaySignatureVerifier verifier,
                                      RazorpayProperties props) {
        this.billing = billing;
        this.verifier = verifier;
        this.props = props;
    }

    @Operation(summary = "Receive a Razorpay webhook",
            description = "Verifies X-Razorpay-Signature against the raw body, then applies "
                    + "the subscription state change. Idempotent on the event id.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> receive(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String headerEventId) {

        if (!props.isWebhookConfigured()) {
            // Refuse rather than accept unverifiable events: silently trusting
            // them would be a free upgrade for anyone who found this URL.
            log.error("Razorpay webhook received but RAZORPAY_WEBHOOK_SECRET is not set — rejecting");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("webhook not configured");
        }

        if (!verifier.verifyWebhook(rawBody, signature)) {
            // Deliberately terse: do not tell a prober why it failed.
            log.warn("Rejected Razorpay webhook with invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
        }

        JsonNode payload;
        try {
            payload = MAPPER.readTree(rawBody);
        } catch (Exception e) {
            log.error("Razorpay webhook passed signature check but body is not JSON", e);
            return ResponseEntity.badRequest().body("malformed body");
        }

        String eventType = payload.path("event").asText("");
        // Razorpay sends the id in a header; older payloads carry it inline.
        String eventId = headerEventId != null && !headerEventId.isBlank()
                ? headerEventId
                : payload.path("id").asText(null);

        try {
            boolean processed = billing.handleWebhook(eventId, eventType, payload, rawBody);
            return ResponseEntity.ok(processed ? "ok" : "duplicate");
        } catch (RuntimeException e) {
            // 500 so Razorpay retries — this is our fault, and the event matters.
            log.error("Failed to process Razorpay webhook {} ({})", eventType, eventId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("processing failed");
        }
    }
}
