package com.agreemint.domain;

/**
 * Commercial plan an organisation is on.
 *
 * <p>Stored as a string in {@code organizations.plan} (VARCHAR(32), no CHECK
 * constraint), so adding a value needs no migration.
 *
 * <p>Declaration order matters: {@link #atLeast} compares by ordinal, so keep
 * these ascending by entitlement.
 */
public enum OrgPlan {
    FREE,
    STARTER,
    PRO,
    ENTERPRISE;

    /** True when this plan includes at least what {@code required} grants. */
    public boolean atLeast(OrgPlan required) {
        return ordinal() >= required.ordinal();
    }

    /** Any plan the customer pays for. */
    public boolean isPaid() {
        return this != FREE;
    }

    /** Plans a customer can buy themselves — ENTERPRISE is sales-led. */
    public boolean isSelfServe() {
        return this == STARTER || this == PRO;
    }
}
