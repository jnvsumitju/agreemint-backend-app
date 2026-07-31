package com.agreemint.api;

import com.agreemint.billing.BillingService;
import com.agreemint.billing.RazorpaySignatureVerifier;
import com.agreemint.config.RazorpayProperties;
import com.agreemint.domain.BillingEvent;
import com.agreemint.domain.BillingPeriod;
import com.agreemint.domain.OrgPlan;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.Subscription;
import com.agreemint.repository.BillingEventRepository;
import com.agreemint.security.OrgAuthorizationService;
import com.agreemint.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Billing for an organisation. Admin-only — the same bar as API keys and
 * webhooks, since this spends the workspace's money.
 *
 * <p>Note the asymmetry with {@link RazorpayWebhookController}: everything here
 * is a request from a signed-in admin, whereas entitlement changes only ever
 * come from a verified webhook.
 */
@Tag(name = "Billing", description = "Subscription and plan management")
@RestController
@RequestMapping("/api/orgs/{orgId}/billing")
public class BillingController {

    private final BillingService billing;
    private final BillingEventRepository eventRepo;
    private final OrgAuthorizationService orgAuthz;
    private final RazorpayProperties props;
    private final RazorpaySignatureVerifier verifier;

    public BillingController(BillingService billing,
                              BillingEventRepository eventRepo,
                              OrgAuthorizationService orgAuthz,
                              RazorpayProperties props,
                              RazorpaySignatureVerifier verifier) {
        this.billing = billing;
        this.eventRepo = eventRepo;
        this.orgAuthz = orgAuthz;
        this.props = props;
        this.verifier = verifier;
    }

    // ── DTOs ──

    public record BillingStatus(
            String plan,
            boolean billingEnabled,
            /** Public Razorpay key so the browser can open Checkout. Never the secret. */
            String razorpayKeyId,
            /** Which (plan, cycle) pairs have a Razorpay Plan configured. */
            List<PurchasablePlan> purchasable,
            SubscriptionSummary subscription
    ) {}

    /** A plan the console may offer, and on which cycles. */
    public record PurchasablePlan(String plan, boolean monthly, boolean yearly) {}

    public record SubscriptionSummary(
            UUID id,
            String razorpaySubscriptionId,
            String status,
            String billingPeriod,
            Instant currentPeriodEnd,
            boolean cancelAtPeriodEnd
    ) {
        static SubscriptionSummary of(Subscription s) {
            return new SubscriptionSummary(s.getId(), s.getRazorpaySubscriptionId(),
                    s.getStatus().name(), s.getBillingPeriod().name(),
                    s.getCurrentPeriodEnd(), s.isCancelAtPeriodEnd());
        }
    }

    public record CreateSubscriptionRequest(String plan, String billingPeriod) {}

    public record CreateSubscriptionResponse(
            String razorpaySubscriptionId,
            String razorpayKeyId,
            String plan,
            String billingPeriod
    ) {}

    public record ConfirmRequest(
            String razorpaySubscriptionId,
            String razorpayPaymentId,
            String razorpaySignature
    ) {}

    public record PaymentRecord(Instant paidAt, Long amount, String currency, String paymentId) {}

    // ── Endpoints ──

    @Operation(summary = "Current plan and subscription state")
    @GetMapping
    public BillingStatus status(@PathVariable UUID orgId,
                                 @AuthenticationPrincipal UserPrincipal principal) {
        orgAuthz.assertRole(principal.userId(), orgId, OrgRole.ADMIN);

        List<PurchasablePlan> purchasable = java.util.Arrays.stream(OrgPlan.values())
                .filter(OrgPlan::isSelfServe)
                .map(p -> new PurchasablePlan(
                        p.name(),
                        !props.planIdFor(p, BillingPeriod.MONTHLY).isBlank(),
                        !props.planIdFor(p, BillingPeriod.YEARLY).isBlank()))
                .filter(p -> p.monthly() || p.yearly())
                .toList();

        return new BillingStatus(
                billing.currentPlan(orgId).name(),
                props.isConfigured(),
                props.getKeyId(),
                purchasable,
                billing.activeSubscription(orgId).map(SubscriptionSummary::of).orElse(null));
    }

    @Operation(summary = "Start a subscription; returns the id to hand to Razorpay Checkout")
    @PostMapping("/subscription")
    public ResponseEntity<CreateSubscriptionResponse> create(
            @PathVariable UUID orgId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody CreateSubscriptionRequest req) {
        orgAuthz.assertRole(principal.userId(), orgId, OrgRole.ADMIN);

        BillingPeriod period = parsePeriod(req == null ? null : req.billingPeriod());
        OrgPlan targetPlan = parsePlan(req == null ? null : req.plan());
        Subscription sub = billing.createSubscription(orgId, targetPlan, period, principal.userId());

        return ResponseEntity.status(HttpStatus.CREATED).body(new CreateSubscriptionResponse(
                sub.getRazorpaySubscriptionId(), props.getKeyId(),
                targetPlan.name(), period.name()));
    }

    @Operation(summary = "Record the browser's checkout result",
            description = "Verifies the signature and refreshes local state. Entitlement is "
                    + "granted by the subscription.activated webhook, not by this call.")
    @PostMapping("/subscription/confirm")
    public SubscriptionSummary confirm(@PathVariable UUID orgId,
                                        @AuthenticationPrincipal UserPrincipal principal,
                                        @RequestBody ConfirmRequest req) {
        orgAuthz.assertRole(principal.userId(), orgId, OrgRole.ADMIN);
        if (req == null || req.razorpaySubscriptionId() == null) {
            throw new BadRequestException("razorpaySubscriptionId is required");
        }
        return SubscriptionSummary.of(billing.confirmCheckout(
                orgId, req.razorpaySubscriptionId(), req.razorpayPaymentId(),
                req.razorpaySignature(), verifier));
    }

    @Operation(summary = "Cancel the subscription",
            description = "Defaults to cancelling at the end of the paid period.")
    @DeleteMapping("/subscription")
    public SubscriptionSummary cancel(@PathVariable UUID orgId,
                                       @AuthenticationPrincipal UserPrincipal principal,
                                       @RequestParam(defaultValue = "false") boolean immediately) {
        orgAuthz.assertRole(principal.userId(), orgId, OrgRole.ADMIN);
        return SubscriptionSummary.of(billing.cancel(orgId, immediately));
    }

    @Operation(summary = "Successful charges, most recent first")
    @GetMapping("/payments")
    public List<PaymentRecord> payments(@PathVariable UUID orgId,
                                         @AuthenticationPrincipal UserPrincipal principal,
                                         @RequestParam(defaultValue = "20") int limit) {
        orgAuthz.assertRole(principal.userId(), orgId, OrgRole.ADMIN);
        int capped = Math.min(Math.max(1, limit), 100);

        return eventRepo
                .findByOrgIdAndEventTypeOrderByCreatedAtDesc(
                        orgId, "subscription.charged", PageRequest.of(0, capped))
                .stream()
                .map(BillingController::toPaymentRecord)
                .toList();
    }

    private static PaymentRecord toPaymentRecord(BillingEvent e) {
        return new PaymentRecord(e.getCreatedAt(), e.getAmount(), e.getCurrency(),
                e.getRazorpayPaymentId());
    }

    /** Defaults to PRO so an older client that omits the field still works. */
    private static OrgPlan parsePlan(String value) {
        if (value == null || value.isBlank()) return OrgPlan.PRO;
        try {
            return OrgPlan.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("plan must be STARTER or PRO");
        }
    }

    private static BillingPeriod parsePeriod(String value) {
        if (value == null || value.isBlank()) return BillingPeriod.MONTHLY;
        try {
            return BillingPeriod.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("billingPeriod must be MONTHLY or YEARLY");
        }
    }
}
