package com.agreemint.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Our record of a Razorpay subscription.
 *
 * <p>Razorpay owns the money; this owns the entitlement. Keeping a local copy
 * means an org's plan can be read on every request without an outbound call,
 * and survives Razorpay being unreachable.
 */
@Entity
@Table(name = "subscriptions")
public class Subscription {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "razorpay_subscription_id", nullable = false, unique = true, length = 64)
    private String razorpaySubscriptionId;

    @Column(name = "razorpay_plan_id", nullable = false, length = 64)
    private String razorpayPlanId;

    @Column(name = "razorpay_customer_id", length = 64)
    private String razorpayCustomerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SubscriptionStatus status = SubscriptionStatus.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrgPlan plan = OrgPlan.PRO;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_period", nullable = false, length = 16)
    private BillingPeriod billingPeriod = BillingPeriod.MONTHLY;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd = false;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /** Whether this subscription should currently grant paid features. */
    public boolean isEntitling() {
        return status.grantsAccess();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public String getRazorpaySubscriptionId() { return razorpaySubscriptionId; }
    public void setRazorpaySubscriptionId(String v) { this.razorpaySubscriptionId = v; }
    public String getRazorpayPlanId() { return razorpayPlanId; }
    public void setRazorpayPlanId(String v) { this.razorpayPlanId = v; }
    public String getRazorpayCustomerId() { return razorpayCustomerId; }
    public void setRazorpayCustomerId(String v) { this.razorpayCustomerId = v; }
    public SubscriptionStatus getStatus() { return status; }
    public void setStatus(SubscriptionStatus status) { this.status = status; }
    public OrgPlan getPlan() { return plan; }
    public void setPlan(OrgPlan plan) { this.plan = plan; }
    public BillingPeriod getBillingPeriod() { return billingPeriod; }
    public void setBillingPeriod(BillingPeriod p) { this.billingPeriod = p; }
    public Instant getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(Instant v) { this.currentPeriodEnd = v; }
    public boolean isCancelAtPeriodEnd() { return cancelAtPeriodEnd; }
    public void setCancelAtPeriodEnd(boolean v) { this.cancelAtPeriodEnd = v; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant v) { this.cancelledAt = v; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
