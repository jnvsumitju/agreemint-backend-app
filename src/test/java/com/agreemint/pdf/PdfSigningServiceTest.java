package com.agreemint.pdf;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.signatures.PdfPKCS7;
import com.itextpdf.signatures.SignatureUtil;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end cover for PAdES signing.
 *
 * <p>Builds a throwaway self-signed certificate, signs a real PDF with it, and
 * checks the result the way a PDF reader would. The property that matters is
 * the last test: a signed document that has been edited must report as altered.
 * Everything else about this feature is decoration if that does not hold.
 */
class PdfSigningServiceTest {

    private static String keystoreBase64;
    private static final String PASSWORD = "test-password";

    @BeforeAll
    static void buildKeystore() throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair pair = kpg.generateKeyPair();

        X500Name subject = new X500Name("CN=Crixaa Test Signing, O=Crixaa");
        Instant now = Instant.now();
        X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(new JcaX509v3CertificateBuilder(
                        subject,
                        BigInteger.valueOf(now.toEpochMilli()),
                        Date.from(now.minus(1, ChronoUnit.DAYS)),
                        Date.from(now.plus(365, ChronoUnit.DAYS)),
                        subject,
                        pair.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256WithRSA")
                                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                                .build(pair.getPrivate())));

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("crixaa", pair.getPrivate(), PASSWORD.toCharArray(),
                new java.security.cert.Certificate[] {cert});

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ks.store(out, PASSWORD.toCharArray());
        keystoreBase64 = Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static PdfSigningService enabledService() {
        PdfSigningService svc = new PdfSigningService(keystoreBase64, PASSWORD, "Issued by Crixaa", "");
        svc.load();
        return svc;
    }

    /** A minimal but genuine PDF. */
    private static byte[] samplePdf() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfDocument doc = new PdfDocument(new PdfWriter(out))) {
            doc.addNewPage();
        }
        return out.toByteArray();
    }

    @Test
    void loadsTheKeyAndReportsAFingerprint() {
        PdfSigningService svc = enabledService();
        assertTrue(svc.isEnabled());
        assertNotNull(svc.certificateFingerprint());
        // Colon-delimited uppercase hex of a SHA-256: 32 bytes, 95 characters.
        assertTrue(svc.certificateFingerprint().matches("^([0-9A-F]{2}:){31}[0-9A-F]{2}$"),
                svc.certificateFingerprint());
    }

    @Test
    void signingProducesAVerifiableSignature() throws Exception {
        byte[] signed = enabledService().sign(samplePdf());

        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(signed)))) {
            SignatureUtil util = new SignatureUtil(doc);
            List<String> names = util.getSignatureNames();
            assertEquals(List.of("CrixaaSignature"), names);

            PdfPKCS7 pkcs7 = util.readSignatureData("CrixaaSignature");
            assertTrue(pkcs7.verifySignatureIntegrityAndAuthenticity(),
                    "the signature should verify against its own certificate");
            // Covers the whole file: nothing was appended after signing.
            assertTrue(util.signatureCoversWholeDocument("CrixaaSignature"));
        }
    }

    @Test
    void editingASignedDocumentBreaksTheSignature() throws Exception {
        byte[] signed = enabledService().sign(samplePdf());

        // Flip a byte the signature demonstrably covers.
        //
        // Picking "the middle of the file" does NOT work, and the reason is
        // worth recording: a PAdES signature covers two ranges with a gap
        // between them, and the gap is where the signature value itself sits.
        // On a small PDF that placeholder is a large hex blob sitting right
        // around the midpoint, so a byte flipped there changes nothing the
        // digest was taken over and the document still verifies. That is
        // correct behaviour, not a hole — nothing readable lives in the gap —
        // but it makes the naive test assert the opposite of what it claims.
        //
        // ByteRange is [start1, length1, start2, length2]; offset 0 to
        // length1 is signed content, so anything late in that first span is
        // real document data.
        int[] byteRange = signedByteRange(signed);
        int covered = byteRange[0] + byteRange[1] - 8;

        byte[] tampered = signed.clone();
        tampered[covered] ^= 0x01;

        boolean detected;
        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(tampered)))) {
            SignatureUtil util = new SignatureUtil(doc);
            PdfPKCS7 pkcs7 = util.readSignatureData("CrixaaSignature");
            detected = !pkcs7.verifySignatureIntegrityAndAuthenticity();
        } catch (Exception structurallyBroken) {
            // Equally acceptable: the edit corrupted the file so badly it no
            // longer parses. Either way no reader shows it as valid.
            detected = true;
        }
        assertTrue(detected, "a modified signed document must not verify");
    }

    /**
     * Appending to a signed PDF — which is how a real editor saves a change —
     * leaves the original signature intact but no longer covering the whole
     * file. Readers report this as "signed, then modified", and
     * {@code signatureCoversWholeDocument} is the check that surfaces it.
     */
    @Test
    void appendingToASignedDocumentIsDetected() throws Exception {
        byte[] signed = enabledService().sign(samplePdf());

        ByteArrayOutputStream appended = new ByteArrayOutputStream();
        appended.write(signed);
        appended.write("\n% an extra byte nobody signed for\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        try (PdfDocument doc = new PdfDocument(
                new PdfReader(new ByteArrayInputStream(appended.toByteArray())))) {
            SignatureUtil util = new SignatureUtil(doc);
            assertFalse(util.signatureCoversWholeDocument("CrixaaSignature"),
                    "content added after signing must not be treated as covered");
        }
    }

    /** Read the signature's ByteRange straight out of the PDF. */
    private static int[] signedByteRange(byte[] pdf) throws Exception {
        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            com.itextpdf.kernel.pdf.PdfArray range = new SignatureUtil(doc)
                    .getSignature("CrixaaSignature")
                    .getByteRange();
            int[] out = new int[range.size()];
            for (int i = 0; i < range.size(); i++) out[i] = range.getAsNumber(i).intValue();
            return out;
        }
    }

    @Test
    void signingIsOffWhenNoKeystoreIsConfigured() {
        PdfSigningService svc = new PdfSigningService("", "", "reason", "");
        svc.load();

        assertFalse(svc.isEnabled());
        assertNull(svc.certificateFingerprint());

        // The document must come back untouched, not null and not empty — an
        // unconfigured signing service cannot be allowed to break generation.
        byte[] pdf = samplePdf();
        assertArrayEquals(pdf, svc.sign(pdf));
    }

    @Test
    void aBrokenKeystoreDisablesSigningRatherThanThrowing() {
        PdfSigningService svc = new PdfSigningService("not-base64-at-all!!", "x", "reason", "");
        assertDoesNotThrow(svc::load);
        assertFalse(svc.isEnabled());

        byte[] pdf = samplePdf();
        assertArrayEquals(pdf, svc.sign(pdf));
    }

    @Test
    void theSignedFileDiffersFromTheInput() {
        // Guards the ordering that the whole feature depends on: signing rewrites
        // the file, so a digest taken before it would describe bytes nobody ever
        // receives. DocumentGenerationService hashes after this call.
        byte[] pdf = samplePdf();
        byte[] signed = enabledService().sign(pdf);

        assertNotEquals(pdf.length, signed.length);
        assertFalse(java.util.Arrays.equals(pdf, signed));
    }
}
