package com.agreemint.billing;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.config.RazorpayProperties;
import com.agreemint.domain.*;
import com.agreemint.repository.BillingEventRepository;
import com.agreemint.repository.OrganizationRepository;
import com.agreemint.repository.SubscriptionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Subscription lifecycle.
 *
 * <p>Division of responsibility: Razorpay is the authority on money and on
 * subscription state; this service mirrors that state locally and translates it
 * into an {@link OrgPlan} on the organisation.
 *
 * <p>Entitlement is granted by <strong>webhooks</strong>, never by the browser.
 * The checkout handback is verified and used for immediate UI feedback, but a
 * client that closes the tab must still end up with the right plan, and a
 * client that lies must not get one.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    /** Statuses that occupy an org's single live-subscription slot. */
    private static final List<SubscriptionStatus> LIVE = List.of(
            SubscriptionStatus.CREATED, SubscriptionStatus.AUTHENTICATED,
            SubscriptionStatus.ACTIVE, SubscriptionStatus.PENDING, SubscriptionStatus.HALTED);

    private final RazorpayClient razorpay;
    private final RazorpayProperties props;
    private final SubscriptionRepository subRepo;
    private final BillingEventRepository eventRepo;
    private final OrganizationRepository orgRepo;
    private final OrgEntitlementService entitlements;
    private final ApiAccessGraceService apiAccessGrace;

    public BillingService(RazorpayClient razorpay,
                           RazorpayProperties props,
                           SubscriptionRepository subRepo,
                           BillingEventRepository eventRepo,
                           OrganizationRepository orgRepo,
                           OrgEntitlementService entitlements,
            ApiAccessGraceService apiAccessGrace) {
        this.razorpay = razorpay;
        this.props = props;
        this.subRepo = subRepo;
        this.eventRepo = eventRepo;
        this.orgRepo = orgRepo;
        this.entitlements = entitlements;
        this.apiAccessGrace = apiAccessGrace;
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<Subscription> activeSubscription(UUID orgId) {
        return subRepo.findFirstByOrgIdAndStatusInOrderByCreatedAtDesc(orgId, LIVE);
    }

    @Transactional(readOnly = true)
    public OrgPlan currentPlan(UUID orgId) {
        return orgRepo.findById(orgId)
                .map(o -> o.getPlan() == null ? OrgPlan.FREE : o.getPlan())
                .orElse(OrgPlan.FREE);
    }

    // ── Checkout ─────────────────────────────────────────────────────────────

    /**
     * Create a Razorpay subscription for the org and return it for Checkout.
     *
     * <p>The subscription starts in {@code created}; the customer has not paid
     * yet, and the org's plan is not touched here.
     */
    @Transactional
    public Subscription createSubscription(UUID orgId, OrgPlan targetPlan,
                                            BillingPeriod period, UUID actingUserId) {
        Organization org = orgRepo.findById(orgId)
                .orElseThrow(() -> new NotFoundException("Organisation not found"));

        if (targetPlan == null || !targetPlan.isSelfServe()) {
            throw new BadRequestException(
                    "Only Starter and Pro can be purchased here. Contact us about Enterprise.");
        }

        String planId = props.planIdFor(targetPlan, period);
        if (planId == null || planId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No Razorpay plan is configured for " + targetPlan + " on " + period
                            + " billing (set the matching RAZORPAY_PLAN_* variable)");
        }

        // One live subscription per org. Re-entering checkout while one is
        // already active would create a second mandate and double-charge.
        activeSubscription(orgId).ifPresent(existing -> {
            throw new BadRequestException(
                    "This workspace already has a subscription. Cancel it before starting a new one.");
        });

        JsonNode created = razorpay.createSubscription(
                planId,
                props.totalCountFor(period),
                Map.of("org_id", orgId.toString(), "org_name", org.getName()));

        Subscription sub = new Subscription();
        sub.setOrgId(orgId);
        sub.setRazorpaySubscriptionId(text(created, "id"));
        sub.setRazorpayPlanId(planId);
        sub.setRazorpayCustomerId(created.path("customer_id").asText(null));
        sub.setStatus(SubscriptionStatus.fromWire(created.path("status").asText("created")));
        sub.setPlan(targetPlan);
        sub.setBillingPeriod(period);
        sub.setCreatedBy(actingUserId);

        try {
            return subRepo.saveAndFlush(sub);
        } catch (DataIntegrityViolationException e) {
            // Lost a race against a concurrent checkout in another tab.
            throw new BadRequestException(
                    "A subscription was just created for this workspace. Refresh and try again.");
        }
    }

    /**
     * Record the browser's checkout handback.
     *
     * <p>Verified for authenticity, but deliberately <strong>not</strong> the
     * thing that grants the plan — {@code subscription.activated} does that.
     * This only moves our local record forward so the UI is not stuck on
     * "pending" while the webhook is in flight.
     */
    @Transactional
    public Subscription confirmCheckout(UUID orgId, String razorpaySubscriptionId,
                                         String paymentId, String signature,
                                         RazorpaySignatureVerifier verifier) {
        Subscription sub = subRepo.findByRazorpaySubscriptionId(razorpaySubscriptionId)
                .orElseThrow(() -> new NotFoundException("Unknown subscription"));

        if (!sub.getOrgId().equals(orgId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Subscription belongs to a different organisation");
        }

        if (!verifier.verifyCheckout(paymentId, razorpaySubscriptionId, signature)) {
            log.warn("Rejected checkout handback with bad signature for subscription {}",
                    razorpaySubscriptionId);
            throw new BadRequestException("Payment signature verification failed");
        }

        // Trust Razorpay's own view over anything the browser told us.
        try {
            JsonNode remote = razorpay.fetchSubscription(razorpaySubscriptionId);
            applyRemoteState(sub, remote);
        } catch (RuntimeException e) {
            // Non-fatal: the webhook will reconcile. Do not fail the customer's
            // checkout because a follow-up read timed out.
            log.warn("Could not re-fetch subscription {} after checkout: {}",
                    razorpaySubscriptionId, e.getMessage());
        }

        return subRepo.save(sub);
    }

    /** Cancel at period end by default, so paid-for time is not forfeited. */
    @Transactional
    public Subscription cancel(UUID orgId, boolean immediately) {
        Subscription sub = activeSubscription(orgId)
                .orElseThrow(() -> new NotFoundException("No active subscription"));

        JsonNode result = razorpay.cancelSubscription(
                sub.getRazorpaySubscriptionId(), !immediately);

        applyRemoteState(sub, result);
        sub.setCancelAtPeriodEnd(!immediately);
        if (immediately) {
            sub.setCancelledAt(Instant.now());
            downgradeToFree(orgId);
        }
        return subRepo.save(sub);
    }

    // ── Webhooks ─────────────────────────────────────────────────────────────

    /**
     * Handle one verified webhook.
     *
     * <p>The caller has already checked the signature. Idempotency is enforced
     * here: the event id is unique, so a redelivery collides and is skipped
     * rather than applying a plan change twice.
     *
     * @return true if this delivery was processed, false if it was a duplicate
     */
    @Transactional
    public boolean handleWebhook(String eventId, String eventType, JsonNode payload, String rawBody) {
        if (eventId != null && eventRepo.existsByRazorpayEventId(eventId)) {
            log.debug("Ignoring duplicate Razorpay event {}", eventId);
            return false;
        }

        JsonNode subNode = payload.path("payload").path("subscription").path("entity");
        JsonNode payNode = payload.path("payload").path("payment").path("entity");

        BillingEvent event = new BillingEvent();
        // Razorpay always sends an id; fall back to a synthetic one so the
        // audit row is still written if that ever changes.
        event.setRazorpayEventId(eventId != null ? eventId : "evt_local_" + UUID.randomUUID());
        event.setEventType(eventType);
        event.setPayload(rawBody);
        if (payNode.hasNonNull("id")) {
            event.setRazorpayPaymentId(payNode.path("id").asText());
            event.setAmount(payNode.path("amount").asLong());
            event.setCurrency(payNode.path("currency").asText(null));
        }

        Subscription sub = null;
        if (subNode.hasNonNull("id")) {
            sub = subRepo.findByRazorpaySubscriptionId(subNode.path("id").asText()).orElse(null);
        }
        // Fall back to the org id we stamped into notes at creation time.
        if (sub == null && subNode.path("notes").hasNonNull("org_id")) {
            log.warn("Webhook {} references unknown subscription {}", eventType,
                    subNode.path("id").asText("?"));
        }

        if (sub != null) {
            event.setSubscriptionId(sub.getId());
            event.setOrgId(sub.getOrgId());
        }

        try {
            eventRepo.saveAndFlush(event);
        } catch (DataIntegrityViolationException e) {
            // Concurrent redelivery won the race — the other one is applying it.
            log.debug("Concurrent duplicate for Razorpay event {}", eventId);
            return false;
        }

        if (sub == null) {
            // Nothing to apply, but the event is recorded for support.
            event.setProcessedAt(Instant.now());
            event.setError("No matching local subscription");
            eventRepo.save(event);
            return true;
        }

        applyEvent(sub, eventType, subNode);

        event.setProcessedAt(Instant.now());
        eventRepo.save(event);
        return true;
    }

    private void applyEvent(Subscription sub, String eventType, JsonNode subNode) {
        applyRemoteState(sub, subNode);

        switch (eventType) {
            // Mandate approved and first payment taken, or a renewal succeeded.
            case "subscription.activated", "subscription.charged", "subscription.resumed" ->
                    upgrade(sub);

            // Retries exhausted, customer cancelled, or the term finished.
            case "subscription.halted", "subscription.cancelled",
                 "subscription.completed", "subscription.expired" ->
                    downgradeToFree(sub.getOrgId());

            // A renewal failed and Razorpay is retrying. Access is retained on
            // purpose — see SubscriptionStatus.grantsAccess.
            case "subscription.pending", "subscription.authenticated",
                 "subscription.paused", "subscription.updated" -> { /* status only */ }

            default -> log.debug("No plan action for Razorpay event {}", eventType);
        }

        subRepo.save(sub);
    }

    private void upgrade(Subscription sub) {
        orgRepo.findById(sub.getOrgId()).ifPresent(org -> {
            if (org.getPlan() != sub.getPlan()) {
                org.setPlan(sub.getPlan());
                orgRepo.save(org);
                log.info("Org {} upgraded to {}", org.getId(), sub.getPlan());
            }
        });
        entitlements.invalidate(sub.getOrgId());
        // Paying again calls off any pending key revocation, so a customer who
        // resubscribes inside the grace window keeps the keys their integration
        // already uses.
        apiAccessGrace.onPlanReactivated(sub.getOrgId());
    }

    private void downgradeToFree(UUID orgId) {
        orgRepo.findById(orgId).ifPresent(org -> {
            if (org.getPlan() != OrgPlan.FREE) {
                org.setPlan(OrgPlan.FREE);
                orgRepo.save(org);
                log.info("Org {} downgraded to FREE", orgId);
            }
        });
        entitlements.invalidate(orgId);
        // The plan is gone immediately; the keys are not. This opens the grace
        // period so the workspace is warned before its integration stops.
        apiAccessGrace.onPlanLapsed(orgId);
    }

    /** Copy Razorpay's view of the subscription onto our row. */
    private void applyRemoteState(Subscription sub, JsonNode node) {
        if (node == null || node.isMissingNode()) return;

        if (node.hasNonNull("status")) {
            try {
                sub.setStatus(SubscriptionStatus.fromWire(node.path("status").asText()));
            } catch (IllegalArgumentException e) {
                // A status we do not know about should not lose us the rest of
                // the update, but it must be visible.
                log.error("Unknown Razorpay subscription status '{}' for {}",
                        node.path("status").asText(), sub.getRazorpaySubscriptionId());
            }
        }
        if (node.hasNonNull("current_end")) {
            long epochSeconds = node.path("current_end").asLong();
            if (epochSeconds > 0) sub.setCurrentPeriodEnd(Instant.ofEpochSecond(epochSeconds));
        }
        if (node.hasNonNull("customer_id")) {
            sub.setRazorpayCustomerId(node.path("customer_id").asText());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Razorpay response is missing " + field);
        }
        return value.asText();
    }
}
