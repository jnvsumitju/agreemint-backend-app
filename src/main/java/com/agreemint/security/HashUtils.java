package com.agreemint.security;

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
}
