package com.agreemint.domain;

/**
 * Billing cycle a subscription is bought on. The actual interval and price live
 * on the corresponding Razorpay Plan; this only selects which plan to use.
 */
public enum BillingPeriod {
    MONTHLY,
    YEARLY
}
