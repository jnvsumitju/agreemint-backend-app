package com.agreemint.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The byte[] digest overload, which fingerprints generated PDFs.
 *
 * <p>Its correctness has to hold against tools we do not control: the
 * verification page hashes with the browser's {@code crypto.subtle.digest},
 * and the page tells people they can check the value themselves with
 * {@code shasum -a 256}. All three must agree on the same bytes or the feature
 * silently accuses honest documents.
 */
class HashUtilsBytesTest {

    /** Published SHA-256 test vectors — independent of any implementation here. */
    @Test
    void matchesTheStandardVectors() {
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                HashUtils.sha256(new byte[0]));
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                HashUtils.sha256("abc".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void theStringOverloadAgreesWithTheByteOverloadForText() {
        String text = "Crixaa document";
        assertEquals(
                HashUtils.sha256(text),
                HashUtils.sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The reason the overload exists.
     *
     * <p>A PDF is full of byte sequences that are not valid UTF-8. Routing them
     * through the String form decodes first, replacing each invalid sequence
     * with U+FFFD — so the digest would be of a corrupted transcription. It
     * would be perfectly stable, and therefore look correct, while being of the
     * wrong input. Worse, unrelated documents would collide: every invalid byte
     * becomes the same replacement character.
     */
    @Test
    void binaryContentIsNotRoutedThroughUtf8Decoding() {
        byte[] a = {(byte) 0xFF, (byte) 0xFE, 0x00, 0x01};
        byte[] b = {(byte) 0xFF, (byte) 0xFD, 0x00, 0x01};

        // Distinct inputs, distinct digests.
        assertNotEquals(HashUtils.sha256(a), HashUtils.sha256(b));

        // But decoded as UTF-8 they are the same string, so a String-based
        // digest cannot tell them apart. This is the collision being avoided.
        String decodedA = new String(a, StandardCharsets.UTF_8);
        String decodedB = new String(b, StandardCharsets.UTF_8);
        assertEquals(decodedA, decodedB, "precondition: both decode to the same replacement chars");
        assertNotEquals(HashUtils.sha256(a), HashUtils.sha256(decodedA));
    }

    @Test
    void oneFlippedBitChangesTheDigest() {
        byte[] pdf = "%PDF-1.7 invoice total 1200".getBytes(StandardCharsets.UTF_8);
        byte[] altered = pdf.clone();
        altered[altered.length - 1] ^= 0x01;

        assertNotEquals(HashUtils.sha256(pdf), HashUtils.sha256(altered));
    }

    @Test
    void theDigestIsLowercaseHexOfTheRightLength() {
        String digest = HashUtils.sha256("anything".getBytes(StandardCharsets.UTF_8));
        // The public endpoint matches on ^[0-9a-f]{64}$, so anything else here
        // would make every generated document unverifiable.
        assertTrue(digest.matches("^[0-9a-f]{64}$"), digest);
    }
}
