package com.agreemint.domain;

import java.util.Set;

/**
 * Mirrors Razorpay's subscription status vocabulary so our record and theirs
 * can be compared without translation.
 *
 * <p>Lifecycle in practice: {@code CREATED} → {@code AUTHENTICATED} (mandate
 * approved) → {@code ACTIVE} (first charge succeeded) → renews. A failed
 * renewal moves to {@code PENDING} while Razorpay retries, then {@code HALTED}
 * when retries are exhausted.
 */
public enum SubscriptionStatus {
    CREATED,
    AUTHENTICATED,
    ACTIVE,
    PENDING,
    HALTED,
    CANCELLED,
    COMPLETED,
    EXPIRED;

    /**
     * Statuses that still occupy the org's single live-subscription slot —
     * must match the partial unique index in V20__billing.sql.
     */
    private static final Set<SubscriptionStatus> OCCUPYING =
            Set.of(CREATED, AUTHENTICATED, ACTIVE, PENDING, HALTED);

    /**
     * Whether the org should currently get paid features.
     *
     * <p>{@code PENDING} still counts: Razorpay is retrying a renewal and the
     * customer has not necessarily done anything wrong, so cutting access on
     * the first failed charge would punish an expired card mid-cycle.
     * {@code HALTED} does not — retries are exhausted by then.
     */
    public boolean grantsAccess() {
        return this == ACTIVE || this == PENDING || this == AUTHENTICATED;
    }

    public boolean occupiesSlot() {
        return OCCUPYING.contains(this);
    }

    /** Parse Razorpay's lowercase wire value; unknown values fail loudly. */
    public static SubscriptionStatus fromWire(String value) {
        if (value == null) throw new IllegalArgumentException("Missing subscription status");
        return valueOf(value.trim().toUpperCase());
    }
}
