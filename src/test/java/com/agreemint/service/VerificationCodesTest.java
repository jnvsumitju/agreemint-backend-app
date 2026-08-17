package com.agreemint.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The printed verification code.
 *
 * <p>Its whole job is to survive being read off paper and typed back in, so the
 * normalisation rules matter as much as the generation.
 */
class VerificationCodesTest {

    @Test
    void looksLikeAPrintableCode() {
        String code = VerificationCodes.generate();
        assertTrue(code.matches("^[0-9A-HJKMNP-TV-Z]{5}-[0-9A-HJKMNP-TV-Z]{5}-[0-9A-HJKMNP-TV-Z]{5}$"), code);
    }

    @Test
    void excludesTheConfusableLetters() {
        // I, L, O and U are absent from Crockford base32 precisely because they
        // are misread as 1, 1, 0 and V. A code containing one could not be
        // reliably transcribed from a printout.
        for (int i = 0; i < 400; i++) {
            String code = VerificationCodes.generate();
            assertFalse(code.contains("I"), code);
            assertFalse(code.contains("L"), code);
            assertFalse(code.contains("O"), code);
            assertFalse(code.contains("U"), code);
        }
    }

    @Test
    void codesDoNotRepeat() {
        // Not a serious collision test — 75 bits makes that untestable — but it
        // would catch a generator accidentally seeded per call or returning a
        // constant, which is the failure that actually happens.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            assertTrue(seen.add(VerificationCodes.generate()), "repeated code");
        }
    }

    @Test
    void acceptsWhatSomeoneWouldActuallyType() {
        String code = VerificationCodes.generate();
        String bare = code.replace("-", "");

        assertEquals(code, VerificationCodes.normalise(code));
        assertEquals(code, VerificationCodes.normalise(bare), "no separators");
        assertEquals(code, VerificationCodes.normalise(code.toLowerCase()), "lowercase");
        assertEquals(code, VerificationCodes.normalise("  " + code + "  "), "surrounding space");
        assertEquals(code, VerificationCodes.normalise(bare.replaceAll("(.....)", "$1 ").trim()),
                "spaces instead of dashes");
    }

    @Test
    void foldsTheSubstitutionsCrockfordDefines() {
        // Someone reading "0" off a page may well type "O". Rejecting that would
        // make the feature feel broken for a mistake the encoding anticipates.
        assertEquals("00000-00000-00000", VerificationCodes.normalise("OOOOO-OOOOO-OOOOO"));
        assertEquals("11111-11111-11111", VerificationCodes.normalise("IIIII-LLLLL-11111"));
    }

    @Test
    void rejectsAnythingThatCannotBeACode() {
        // U has no digit to fold into — it was never in the alphabet, so a code
        // containing one is simply not one of ours.
        assertNull(VerificationCodes.normalise("UUUUU-UUUUU-UUUUU"));
        assertNull(VerificationCodes.normalise(null));
        assertNull(VerificationCodes.normalise(""));
        assertNull(VerificationCodes.normalise("too-short"));
        assertNull(VerificationCodes.normalise(VerificationCodes.generate() + "X"), "too long");
        assertNull(VerificationCodes.normalise("../../etc/passwd"));
        assertNull(VerificationCodes.normalise("8FK2M-9QTX4-M7PW!"));
    }

    @Test
    void normalisationIsIdempotent() {
        String once = VerificationCodes.normalise(VerificationCodes.generate().toLowerCase());
        assertEquals(once, VerificationCodes.normalise(once));
    }
}
