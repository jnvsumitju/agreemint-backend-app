package com.agreemint.billing;

import com.agreemint.config.RazorpayProperties;
import com.agreemint.security.HashUtils;
import org.springframework.stereotype.Component;

/**
 * Verifies the two distinct Razorpay signatures. They use different secrets and
 * different payloads, and mixing them up silently accepts forged requests, so
 * they live side by side here with the difference spelled out.
 *
 * <p>Both comparisons are constant-time.
 */
@Component
public class RazorpaySignatureVerifier {

    private final RazorpayProperties props;

    public RazorpaySignatureVerifier(RazorpayProperties props) {
        this.props = props;
    }

    /**
     * Checkout handback, returned in the browser after the customer approves the
     * mandate.
     *
     * <p>Signed with the <strong>API key secret</strong> over
     * {@code payment_id + "|" + subscription_id}. Note the argument order —
     * Razorpay documents the reverse order for one-time Orders, and swapping
     * them here would reject every legitimate payment.
     *
     * <p>This proves the browser is reporting a real payment, but it is not the
     * authority on entitlement: a client can simply never call back. Webhooks
     * are what actually grant access.
     */
    public boolean verifyCheckout(String paymentId, String subscriptionId, String signature) {
        if (paymentId == null || subscriptionId == null || signature == null) return false;
        String expected = HashUtils.hmacSha256Hex(
                props.getKeySecret(), paymentId + "|" + subscriptionId);
        return HashUtils.constantTimeEquals(expected, signature);
    }

    /**
     * Webhook delivery.
     *
     * <p>Signed with the <strong>webhook secret</strong> (set on the webhook in
     * the Razorpay dashboard — a different value from the API key secret) over
     * the raw request body.
     *
     * <p>{@code rawBody} must be the exact bytes received. Deserialising and
     * re-serialising changes whitespace and key order, and the signature will
     * never match.
     */
    public boolean verifyWebhook(String rawBody, String signature) {
        if (rawBody == null || signature == null || !props.isWebhookConfigured()) return false;
        String expected = HashUtils.hmacSha256Hex(props.getWebhookSecret(), rawBody);
        return HashUtils.constantTimeEquals(expected, signature);
    }
}
