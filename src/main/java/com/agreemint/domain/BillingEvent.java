package com.agreemint.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A webhook Razorpay sent us, recorded before it is acted on.
 *
 * <p>{@link #razorpayEventId} is unique. Razorpay retries deliveries, so
 * inserting first turns a replay into a constraint violation we can swallow,
 * rather than a second plan change or a duplicated payment record.
 */
@Entity
@Table(name = "billing_events")
public class BillingEvent {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "razorpay_event_id", nullable = false, unique = true, length = 64)
    private String razorpayEventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "subscription_id")
    private UUID subscriptionId;

    /** Smallest currency unit (paise), exactly as Razorpay sends it. */
    @Column
    private Long amount;

    @Column(length = 8)
    private String currency;

    @Column(name = "razorpay_payment_id", length = 64)
    private String razorpayPaymentId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getRazorpayEventId() { return razorpayEventId; }
    public void setRazorpayEventId(String v) { this.razorpayEventId = v; }
    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID v) { this.orgId = v; }
    public UUID getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(UUID v) { this.subscriptionId = v; }
    public Long getAmount() { return amount; }
    public void setAmount(Long v) { this.amount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public String getRazorpayPaymentId() { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String v) { this.razorpayPaymentId = v; }
    public String getPayload() { return payload; }
    public void setPayload(String v) { this.payload = v; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant v) { this.processedAt = v; }
    public String getError() { return error; }
    public void setError(String v) { this.error = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
