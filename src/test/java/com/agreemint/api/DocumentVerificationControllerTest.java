package com.agreemint.api;

import com.agreemint.api.public_.DocumentVerificationController;
import com.agreemint.pdf.PdfSigningService;
import com.agreemint.domain.DocumentReceipt;
import com.agreemint.repository.DocumentReceiptRepository;
import com.agreemint.security.HashUtils;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The public verification endpoint.
 *
 * <p>This is the only unauthenticated route in the application that reads
 * customer data, so its edges matter more than most: it must answer the same
 * way for a malformed digest as for an unknown one, must never accept a
 * document id, and must not fall over when Redis is down.
 */
class DocumentVerificationControllerTest {

    private DocumentReceiptRepository receipts;
    private DocumentVerificationController controller;
    private HttpServletRequest http;
    private PdfSigningService signing;

    /** Stands in for a real PDF; only its bytes matter here. */
    private static final byte[] PDF = "%PDF-1.7 pretend document".getBytes(StandardCharsets.UTF_8);
    private static final String DIGEST = HashUtils.sha256(PDF);

    @BeforeEach
    void setUp() {
        receipts = mock(DocumentReceiptRepository.class);
        signing = mock(PdfSigningService.class);
        controller = new DocumentVerificationController(receipts, unlimitedBuckets(), signing, 30);
        http = mock(HttpServletRequest.class);
        when(http.getRemoteAddr()).thenReturn("198.51.100.7");
    }

    @Test
    void anIssuedDocumentVerifies() {
        when(receipts.findBySha256OrderByIssuedAtAsc(DIGEST)).thenReturn(List.of(receipt(DIGEST)));

        var res = controller.verify(new DocumentVerificationController.VerifyRequest(DIGEST), http);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertTrue(res.getBody().verified());
        assertNotNull(res.getBody().issuedAt());
        assertEquals((long) PDF.length, res.getBody().byteSize());
    }

    @Test
    void aModifiedDocumentDoesNotVerify() {
        // One byte different — the change a forger would make.
        byte[] tampered = PDF.clone();
        tampered[tampered.length - 1] ^= 0x01;
        String tamperedDigest = HashUtils.sha256(tampered);
        assertNotEquals(DIGEST, tamperedDigest);

        when(receipts.findBySha256OrderByIssuedAtAsc(tamperedDigest)).thenReturn(List.of());

        var res = controller.verify(
                new DocumentVerificationController.VerifyRequest(tamperedDigest), http);

        assertFalse(res.getBody().verified());
        assertNull(res.getBody().issuedAt());
    }

    @Test
    void uppercaseDigestsAreAccepted() {
        // Plenty of tools print SHA-256 in uppercase; rejecting those would be
        // a support burden with no security value.
        when(receipts.findBySha256OrderByIssuedAtAsc(DIGEST)).thenReturn(List.of(receipt(DIGEST)));

        var res = controller.verify(
                new DocumentVerificationController.VerifyRequest(DIGEST.toUpperCase()), http);

        assertTrue(res.getBody().verified());
    }

    @Test
    void aDocumentIdIsNotAcceptedAsALookupKey() {
        // A document UUID appears in the file URL, the storage key, the download
        // filename and the webhook payload. If it worked here, anyone who had
        // seen one in a log could confirm a document exists without ever holding
        // the file.
        var res = controller.verify(
                new DocumentVerificationController.VerifyRequest(UUID.randomUUID().toString()), http);

        assertFalse(res.getBody().verified());
        verify(receipts, never()).findBySha256OrderByIssuedAtAsc(anyString());
    }

    @Test
    void malformedInputIsAnsweredExactlyLikeAnUnknownDocument() {
        for (String bad : new String[] {null, "", "   ", "zz", "not-a-digest", DIGEST + "extra"}) {
            var res = controller.verify(
                    new DocumentVerificationController.VerifyRequest(bad), http);
            assertEquals(HttpStatus.OK, res.getStatusCode(), "input: " + bad);
            assertFalse(res.getBody().verified(), "input: " + bad);
            assertNull(res.getBody().issuedAt(), "input: " + bad);
        }
        verify(receipts, never()).findBySha256OrderByIssuedAtAsc(anyString());
    }

    @Test
    void aNullBodyDoesNotThrow() {
        var res = controller.verify(null, http);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertFalse(res.getBody().verified());
    }

    @Test
    void theResponseCarriesNothingBeyondMatchAndIssuance() {
        // Guards the privacy contract by construction: if someone later adds an
        // org name or template title to the record, this fails.
        when(receipts.findBySha256OrderByIssuedAtAsc(DIGEST)).thenReturn(List.of(receipt(DIGEST)));
        var body = controller.verify(
                new DocumentVerificationController.VerifyRequest(DIGEST), http).getBody();

        var components = DocumentVerificationController.VerifyResponse.class.getRecordComponents();
        assertEquals(3, components.length,
                "VerifyResponse gained a field — check it leaks nothing about the issuer");
        assertEquals("verified", components[0].getName());
        assertEquals("issuedAt", components[1].getName());
        assertEquals("byteSize", components[2].getName());
        assertNotNull(body);
    }

    @Test
    void exhaustingTheRateLimitReturns429() {
        controller = new DocumentVerificationController(receipts, exhaustedBuckets(), signing, 30);

        var res = controller.verify(new DocumentVerificationController.VerifyRequest(DIGEST), http);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, res.getStatusCode());
        verify(receipts, never()).findBySha256OrderByIssuedAtAsc(anyString());
    }

    @Test
    void verificationStillWorksWhenRedisIsDown() {
        // Fails open on purpose. A limiter that takes the feature down with it
        // is worse than the traffic it guards against — someone checking whether
        // a contract is genuine should not be told "try later" because a cache
        // is unavailable.
        ProxyManager<String> broken = mock(ProxyManager.class, RETURNS_DEEP_STUBS);
        when(broken.builder()).thenThrow(new IllegalStateException("redis down"));
        controller = new DocumentVerificationController(receipts, broken, signing, 30);
        when(receipts.findBySha256OrderByIssuedAtAsc(DIGEST)).thenReturn(List.of(receipt(DIGEST)));

        var res = controller.verify(new DocumentVerificationController.VerifyRequest(DIGEST), http);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertTrue(res.getBody().verified());
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private DocumentReceipt receipt(String sha256) {
        DocumentReceipt r = new DocumentReceipt();
        r.setDocumentId(UUID.randomUUID());
        r.setOrgId(UUID.randomUUID());
        r.setSha256(sha256);
        r.setByteSize(PDF.length);
        r.setIssuedAt(Instant.parse("2026-08-16T10:15:30Z"));
        return r;
    }

    @SuppressWarnings("unchecked")
    private static ProxyManager<String> unlimitedBuckets() {
        ProxyManager<String> pm = mock(ProxyManager.class, RETURNS_DEEP_STUBS);
        when(pm.builder().build(anyString(), any(java.util.function.Supplier.class)).tryConsume(1))
                .thenReturn(true);
        return pm;
    }

    @SuppressWarnings("unchecked")
    private static ProxyManager<String> exhaustedBuckets() {
        ProxyManager<String> pm = mock(ProxyManager.class, RETURNS_DEEP_STUBS);
        when(pm.builder().build(anyString(), any(java.util.function.Supplier.class)).tryConsume(1))
                .thenReturn(false);
        return pm;
    }
}
