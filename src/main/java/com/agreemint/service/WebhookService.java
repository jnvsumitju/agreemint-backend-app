package com.agreemint.service;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.CreateWebhookRequest;
import com.agreemint.api.dto.WebhookCreatedResponse;
import com.agreemint.api.dto.WebhookDeliveryResponse;
import com.agreemint.api.dto.WebhookResponse;
import com.agreemint.domain.Webhook;
import com.agreemint.domain.WebhookDelivery;
import com.agreemint.repository.WebhookDeliveryRepository;
import com.agreemint.repository.WebhookRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * CRUD over {@link Webhook} plus the {@link #emit(UUID, String, Object)} entry
 * point that other services call to enqueue deliveries. Actual HTTP POSTing
 * happens asynchronously in {@link WebhookDispatchJob}.
 */
@Service
public class WebhookService {

    /** Whitelist of emittable event names — see plan Phase 4. */
    public static final Set<String> KNOWN_EVENTS = Set.of(
            "document.generated",
            "review.requested",
            "review.decided",
            "template.version.committed"
    );

    private static final SecureRandom RNG = new SecureRandom();
    private static final int SECRET_BYTES = 32; // → 64 hex chars

    private final WebhookRepository webhookRepo;
    private final WebhookDeliveryRepository deliveryRepo;
    private final ActivityService activityService;
    private final ObjectMapper objectMapper;

    public WebhookService(
            WebhookRepository webhookRepo,
            WebhookDeliveryRepository deliveryRepo,
            ActivityService activityService,
            ObjectMapper objectMapper) {
        this.webhookRepo = webhookRepo;
        this.deliveryRepo = deliveryRepo;
        this.activityService = activityService;
        this.objectMapper = objectMapper;
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @Transactional
    public WebhookCreatedResponse create(UUID orgId, UUID actorId, String actorName,
                                         CreateWebhookRequest req) {
        if (req == null || req.url() == null || req.url().isBlank()) {
            throw new BadRequestException("url is required");
        }
        if (req.events() == null || req.events().isEmpty()) {
            throw new BadRequestException("at least one event is required");
        }
        String url = req.url().trim();
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new BadRequestException("url must be http(s)://…");
        }
        for (String e : req.events()) {
            if (!KNOWN_EVENTS.contains(e)) throw new BadRequestException("Unknown event: " + e);
        }

        String secret = generateSecret();
        Webhook w = new Webhook();
        w.setOrgId(orgId);
        w.setCreatedBy(actorId);
        w.setUrl(url);
        w.setSecret(secret);
        w.setSecretLast4(secret.substring(secret.length() - 4));
        w.setEvents(String.join(",", req.events()));
        w.setActive(true);
        Webhook saved = webhookRepo.save(w);
        activityService.log(orgId, actorId, actorName, "WEBHOOK_CREATED",
                "WEBHOOK", saved.getId(), url);
        return new WebhookCreatedResponse(WebhookResponse.from(saved), secret);
    }

    @Transactional
    public void revoke(UUID orgId, UUID actorId, String actorName, UUID webhookId) {
        Webhook w = webhookRepo.findById(webhookId)
                .orElseThrow(() -> new NotFoundException("Webhook not found"));
        if (!w.getOrgId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your org");
        }
        if (w.getRevokedAt() != null) return;
        w.setRevokedAt(Instant.now());
        w.setActive(false);
        webhookRepo.save(w);
        activityService.log(orgId, actorId, actorName, "WEBHOOK_REVOKED",
                "WEBHOOK", w.getId(), w.getUrl());
    }

    @Transactional(readOnly = true)
    public List<WebhookResponse> list(UUID orgId) {
        return webhookRepo.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(WebhookResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<WebhookDeliveryResponse> listDeliveries(UUID orgId, UUID webhookId, int limit) {
        Webhook w = webhookRepo.findById(webhookId)
                .orElseThrow(() -> new NotFoundException("Webhook not found"));
        if (!w.getOrgId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your org");
        }
        return deliveryRepo.findByWebhookIdOrderByCreatedAtDesc(
                    webhookId, PageRequest.of(0, Math.min(Math.max(1, limit), 200)))
                .stream().map(WebhookDeliveryResponse::from).toList();
    }

    // ── Emit ─────────────────────────────────────────────────────────────────

    /**
     * Enqueue a {@code PENDING} delivery row for every active webhook in the
     * given org that's subscribed to {@code event}. Never throws — downstream
     * services keep running even when the webhook table is temporarily unhappy.
     */
    @Transactional
    public void emit(UUID orgId, String event, Object payloadObj) {
        if (orgId == null || event == null) return;
        if (!KNOWN_EVENTS.contains(event)) return;
        String payload;
        try {
            payload = objectMapper.writeValueAsString(payloadObj);
        } catch (JsonProcessingException e) {
            return;
        }
        List<Webhook> hooks = webhookRepo
                .findByOrgIdAndActiveTrueAndRevokedAtIsNullOrderByCreatedAtDesc(orgId);
        for (Webhook w : hooks) {
            if (!w.eventSet().contains(event)) continue;
            WebhookDelivery d = new WebhookDelivery();
            d.setWebhookId(w.getId());
            d.setEvent(event);
            d.setPayload(payload);
            d.setStatus(WebhookDelivery.Status.PENDING);
            d.setNextRetryAt(Instant.now());
            deliveryRepo.save(d);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String generateSecret() {
        byte[] buf = new byte[SECRET_BYTES];
        RNG.nextBytes(buf);
        return "whsec_" + HexFormat.of().formatHex(buf);
    }
}
