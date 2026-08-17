package com.agreemint.service;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Short codes printed on documents that carry a visible verification mark.
 *
 * <p>Random, not derived. A code computed from the document id would mean that
 * anyone who had seen an id — they appear in webhook payloads and file URLs —
 * could produce the matching code, quietly turning every leaked id into a
 * working lookup key. Random codes have no relationship to each other or to
 * anything else.
 *
 * <p>Crockford base32: the digits and uppercase letters, minus I, L, O and U.
 * Removing those is what makes the code safe to read off a printed page or
 * repeat over the phone — there is no character pair left that a person can
 * reasonably confuse, and the decoder folds the obvious mistakes back anyway.
 */
public final class VerificationCodes {

    private VerificationCodes() {}

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int GROUPS = 3;
    private static final int GROUP_LEN = 5;
    /** 15 characters × 5 bits = 75 bits of entropy. */
    private static final int LENGTH = GROUPS * GROUP_LEN;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A fresh code, formatted for printing: {@code 8FK2M-9QTX4-M7PWR}. */
    public static String generate() {
        StringBuilder out = new StringBuilder(LENGTH + GROUPS - 1);
        for (int i = 0; i < LENGTH; i++) {
            if (i > 0 && i % GROUP_LEN == 0) out.append('-');
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }

    /**
     * Normalise something a person typed into the canonical stored form.
     *
     * <p>Accepts lowercase, missing or extra separators, and the four
     * substitutions Crockford defines for characters it excludes: {@code I} and
     * {@code L} read as {@code 1}, {@code O} reads as {@code 0}. {@code U} is
     * excluded from the alphabet but has no digit to fold into, so a code
     * containing one was never valid and stays invalid.
     *
     * @return the canonical form, or null when the input cannot be one of our
     *         codes at all
     */
    public static String normalise(String raw) {
        if (raw == null) return null;
        StringBuilder chars = new StringBuilder(LENGTH);
        for (char c : raw.toUpperCase(Locale.ROOT).toCharArray()) {
            char folded = switch (c) {
                case 'I', 'L' -> '1';
                case 'O' -> '0';
                default -> c;
            };
            if (ALPHABET.indexOf(folded) >= 0) {
                if (chars.length() == LENGTH) return null; // too long
                chars.append(folded);
            } else if (folded != '-' && folded != ' ') {
                return null; // a character that cannot appear in any code
            }
        }
        if (chars.length() != LENGTH) return null;

        StringBuilder out = new StringBuilder(LENGTH + GROUPS - 1);
        for (int i = 0; i < LENGTH; i++) {
            if (i > 0 && i % GROUP_LEN == 0) out.append('-');
            out.append(chars.charAt(i));
        }
        return out.toString();
    }
}
