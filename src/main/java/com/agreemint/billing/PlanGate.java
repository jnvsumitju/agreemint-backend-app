package com.agreemint.billing;

import com.agreemint.domain.OrgPlan;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Feature gating by plan.
 *
 * <p>Answers 402 Payment Required rather than 403: this is a billing state the
 * customer can resolve themselves, not a permissions problem. The console keys
 * its upgrade prompt off that status.
 *
 * <p>Reads through {@link OrgEntitlementService}, which caches for a minute, so
 * gating a request costs no extra database round trip.
 *
 * <p><strong>Gate writes, not reads.</strong> Blocking a read strands data a
 * customer already created — if a subscription lapses, they should still be
 * able to see their approval history, just not start new workflows.
 */
@Service
public class PlanGate {

    private final OrgEntitlementService entitlements;

    public PlanGate(OrgEntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    /** Throw unless the org is on a paid plan. */
    public void requirePaid(UUID orgId, String feature) {
        if (!isPaid(orgId)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    feature + " is available on Pro. Upgrade in Settings → Billing.");
        }
    }

    public boolean isPaid(UUID orgId) {
        if (orgId == null) return false;
        OrgPlan plan = entitlements.resolve(orgId).plan();
        return plan != null && plan != OrgPlan.FREE;
    }
}
