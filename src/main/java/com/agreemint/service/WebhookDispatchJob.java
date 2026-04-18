package com.agreemint.service;

import com.agreemint.domain.Webhook;
import com.agreemint.domain.WebhookDelivery;
import com.agreemint.domain.WebhookDelivery.Status;
import com.agreemint.repository.WebhookDeliveryRepository;
import com.agreemint.repository.WebhookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Dispatcher for pending {@link WebhookDelivery} rows.
 *
 * <p>Every {@value #POLL_MS} ms it picks up to {@value #BATCH} deliveries whose
 * {@code next_retry_at} has arrived, POSTs the payload to the target URL with
 * HMAC-SHA256 signature + timestamp headers, and records the outcome. Retries
 * use exponential backoff up to {@code maxAttempts} before the row becomes
 * {@link Status#ABANDONED}.
 *
 * <p>The signature scheme mirrors Stripe's — customers can verify with a
 * standard HMAC library and the raw secret they saw once at creation time.
 */
@Component
public class WebhookDispatchJob {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchJob.class);
    private static final int BATCH = 25;
    private static final long POLL_MS = 2_000;
    private static final int RESPONSE_BODY_MAX = 2048;

    private final WebhookDeliveryRepository deliveryRepo;
    private final WebhookRepository webhookRepo;
    private final HttpClient http;

    public WebhookDispatchJob(WebhookDeliveryRepository deliveryRepo, WebhookRepository webhookRepo) {
        this.deliveryRepo = deliveryRepo;
        this.webhookRepo = webhookRepo;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Scheduled(fixedDelay = POLL_MS)
    @Transactional
    public void poll() {
        List<WebhookDelivery> due = deliveryRepo
                .findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                        Status.PENDING, Instant.now(), PageRequest.of(0, BATCH));
        for (WebhookDelivery d : due) {
            dispatchOne(d);
        }
    }

    private void dispatchOne(WebhookDelivery d) {
        Webhook hook = webhookRepo.findById(d.getWebhookId()).orElse(null);
        if (hook == null || !hook.isLive()) {
            d.setStatus(Status.ABANDONED);
            d.setError("Webhook revoked or deleted");
            deliveryRepo.save(d);
            return;
        }

        long epoch = Instant.now().getEpochSecond();
        String signedPayload = epoch + "." + d.getPayload();
        String signature = "t=" + epoch + ",v1=" + hmacSha256Hex(hook.getSecret(), signedPayload);

        HttpRequest req = HttpRequest.newBuilder(URI.create(hook.getUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Agreemint-Webhooks/1.0")
                .header("X-Agreemint-Event", d.getEvent())
                .header("X-Agreemint-Delivery", d.getId().toString())
                .header("X-Agreemint-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(d.getPayload(), StandardCharsets.UTF_8))
                .build();

        d.setAttempt(d.getAttempt() + 1);

        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            d.setResponseCode(res.statusCode());
            d.setResponseBody(truncate(res.body()));
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                d.setStatus(Status.SUCCEEDED);
                d.setDeliveredAt(Instant.now());
                d.setNextRetryAt(null);
                d.setError(null);
            } else {
                scheduleRetryOrAbandon(d, "HTTP " + res.statusCode());
            }
        } catch (Exception e) {
            d.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
            scheduleRetryOrAbandon(d, d.getError());
        }
        deliveryRepo.save(d);
    }

    private static void scheduleRetryOrAbandon(WebhookDelivery d, String note) {
        if (d.getAttempt() >= d.getMaxAttempts()) {
            d.setStatus(Status.ABANDONED);
            d.setNextRetryAt(null);
        } else {
            // 2, 4, 8, 16 … seconds. Capped at 1 h so very long stalls don't
            // stretch indefinitely.
            long delaySec = Math.min(3600, 1L << d.getAttempt());
            d.setStatus(Status.PENDING);
            d.setNextRetryAt(Instant.now().plusSeconds(delaySec));
        }
        if (note != null && d.getError() == null) d.setError(note);
    }

    private static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            log.error("HMAC failure — JRE missing HmacSHA256?", e);
            return "";
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > RESPONSE_BODY_MAX ? s.substring(0, RESPONSE_BODY_MAX) : s;
    }
}
