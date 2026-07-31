package com.agreemint.billing;

import com.agreemint.config.FreePlanProperties;
import com.agreemint.domain.OrgPlan;
import com.agreemint.repository.OrganizationRepository;
import com.agreemint.repository.TemplateRepository;
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
    private final FreePlanProperties freeLimits;
    private final OrganizationRepository orgRepo;
    private final TemplateRepository templateRepo;

    public PlanGate(OrgEntitlementService entitlements,
                     FreePlanProperties freeLimits,
                     OrganizationRepository orgRepo,
                     TemplateRepository templateRepo) {
        this.entitlements = entitlements;
        this.freeLimits = freeLimits;
        this.orgRepo = orgRepo;
        this.templateRepo = templateRepo;
    }

    // ── Free-plan limits ─────────────────────────────────────────────────────

    /**
     * Whether this workspace is subject to the free-plan limits.
     *
     * <p>Two conditions, both required: it is on FREE, and it was created at or
     * after the configured cutover. Workspaces that predate the cutover are
     * grandfathered — they keep the terms they signed up under.
     */
    public boolean isFreeRestricted(UUID orgId) {
        if (orgId == null || !freeLimits.isEnabled()) return false;
        if (planOf(orgId) != OrgPlan.FREE) return false;
        return orgRepo.findById(orgId)
                .map(org -> freeLimits.appliesTo(org.getCreatedAt()))
                .orElse(false);
    }

    /** Throw when a restricted free workspace is at its template ceiling. */
    public void requireTemplateHeadroom(UUID orgId) {
        if (!isFreeRestricted(orgId)) return;

        long existing = templateRepo.countByOrgId(orgId);
        if (existing >= freeLimits.getMaxTemplates()) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "The free plan is limited to " + freeLimits.getMaxTemplates()
                            + " templates. Upgrade in Settings → Billing, or delete one you no"
                            + " longer need.");
        }
    }

    /**
     * Throw when a user on free is trying to create another workspace.
     *
     * <p>Counts workspaces the user already administers. A user who belongs to
     * someone else's paid workspace is not blocked by that workspace's plan —
     * this is about workspaces they own.
     *
     * <p>Grandfathering needs a different anchor here than everywhere else: the
     * workspace being created has no creation date yet, so we look at the
     * user's OLDEST existing workspace instead. Someone who was here before the
     * cutover keeps the old behaviour, which is what "grandfather existing
     * users" has to mean for an action that creates something new.
     *
     * @param oldestOwnedAt creation time of the user's earliest owned
     *                      workspace, or null if they own none
     */
    public void requireWorkspaceHeadroom(long ownedWorkspaces, boolean anyOwnedIsPaid,
                                          java.time.Instant oldestOwnedAt) {
        if (!freeLimits.isEnabled() || anyOwnedIsPaid) return;
        // Predates the cutover → exempt.
        if (oldestOwnedAt != null && !freeLimits.appliesTo(oldestOwnedAt)) return;
        if (ownedWorkspaces >= freeLimits.getMaxWorkspaces()) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "The free plan includes " + freeLimits.getMaxWorkspaces()
                            + " workspace. Upgrade to create more.");
        }
    }

    /**
     * Throw unless the org is on {@code minimum} or better.
     *
     * <p>This is the one to use. Tiers differ — version history is Starter and
     * up, while approvals and lifecycle are Pro — so "is it paid?" is not a
     * fine enough question, and using it would hand Starter customers features
     * they did not buy.
     *
     * <p>The message names the required plan so the console can say something
     * more useful than "upgrade".
     */
    public void requireAtLeast(UUID orgId, OrgPlan minimum, String feature) {
        if (!hasAtLeast(orgId, minimum)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    feature + " is available on " + friendly(minimum)
                            + " and above. Upgrade in Settings → Billing.");
        }
    }

    public boolean hasAtLeast(UUID orgId, OrgPlan minimum) {
        return planOf(orgId).atLeast(minimum);
    }

    /** Any paid plan. Prefer {@link #requireAtLeast} for feature checks. */
    public boolean isPaid(UUID orgId) {
        return planOf(orgId).isPaid();
    }

    private OrgPlan planOf(UUID orgId) {
        if (orgId == null) return OrgPlan.FREE;
        OrgPlan plan = entitlements.resolve(orgId).plan();
        return plan == null ? OrgPlan.FREE : plan;
    }

    private static String friendly(OrgPlan plan) {
        return plan.name().charAt(0) + plan.name().substring(1).toLowerCase();
    }
}
