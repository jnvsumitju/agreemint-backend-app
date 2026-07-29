package com.agreemint.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Tiny shared hashing helpers. Previously duplicated inside
 * {@code AuthService.sha256(...)}; lifted here so other services (API keys,
 * webhooks) don't have to depend on AuthService.
 */
public final class HashUtils {

    private HashUtils() {}

    /** SHA-256 hex digest of the input string (UTF-8). */
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every JRE we target — this branch is unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** HMAC-SHA256 of {@code message} keyed by {@code secret}, as lowercase hex. */
    public static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * Constant-time comparison of two hex signatures.
     *
     * <p>A plain {@code equals} leaks how many leading characters matched via
     * timing, which is enough to forge a signature byte by byte. Always use
     * this when comparing a value an attacker controls against a secret-derived
     * one.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
