package com.agreemint.pdf;

import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.StampingProperties;
import com.itextpdf.signatures.BouncyCastleDigest;
import com.itextpdf.signatures.IExternalSignature;
import com.itextpdf.signatures.PdfSigner;
import com.itextpdf.signatures.PrivateKeySignature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HexFormat;

/**
 * Applies a PAdES digital signature to a generated PDF.
 *
 * <p>This is the only part of verification that works with no network and no
 * visit to us: Adobe Reader and most other viewers check the signature
 * themselves and flag any subsequent edit. The digest registry proves we issued
 * a file; the signature proves the file has not moved since, to software the
 * recipient already trusts.
 *
 * <p><b>Self-signed, and what that costs.</b> Readers will show
 * "signature validity is unknown" rather than a green tick, because no public
 * certificate authority vouches for the key. The integrity maths is identical —
 * a modified document still reports as altered — but the identity claim is not
 * corroborated. That is a deliberate trade: a publicly trusted document-signing
 * certificate cannot be held as a file at all under current CA/Browser Forum
 * rules, so it would mean a network call to an HSM inside every generation.
 * The mitigation is to publish the certificate's SHA-256 fingerprint where
 * recipients can compare it — {@link #certificateFingerprint()} exists for that
 * and is surfaced on the public verification page.
 *
 * <p><b>Key material is a base64 PKCS#12 in an environment variable</b>, which
 * matches how every other secret here is handled and adds no new deployment
 * primitive. Absent configuration disables signing entirely rather than failing
 * generation — an unsigned document is a working document.
 */
@Service
public class PdfSigningService {

    private static final Logger log = LoggerFactory.getLogger(PdfSigningService.class);

    private final String keystoreBase64;
    private final String keystorePassword;
    private final String reason;
    private final String location;

    private PrivateKey privateKey;
    private Certificate[] chain;
    private String fingerprint;

    public PdfSigningService(
            @Value("${agreemint.signing.keystore-base64:}") String keystoreBase64,
            @Value("${agreemint.signing.keystore-password:}") String keystorePassword,
            @Value("${agreemint.signing.reason:Issued by Crixaa}") String reason,
            @Value("${agreemint.signing.location:}") String location) {
        this.keystoreBase64 = keystoreBase64;
        this.keystorePassword = keystorePassword;
        this.reason = reason;
        this.location = location;
    }

    /**
     * Load the key once at startup.
     *
     * <p>Deliberately not lazy. A malformed keystore should be visible in the
     * logs the moment the service comes up, not discovered on the first
     * customer document of the day.
     */
    @PostConstruct
    void load() {
        if (keystoreBase64 == null || keystoreBase64.isBlank()) {
            log.info("PDF signing disabled — no signing keystore configured");
            return;
        }
        try {
            byte[] p12 = Base64.getDecoder().decode(keystoreBase64.replaceAll("\\s", ""));
            char[] password = keystorePassword == null ? new char[0] : keystorePassword.toCharArray();

            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(p12), password);

            // PrivateKeySignature resolves its algorithm through a named JCE
            // provider, so BC has to be registered before the first signature —
            // not merely on the classpath.
            if (java.security.Security.getProvider(
                    org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME) == null) {
                java.security.Security.addProvider(
                        new org.bouncycastle.jce.provider.BouncyCastleProvider());
            }

            String alias = firstKeyAlias(ks);
            if (alias == null) {
                log.error("PDF signing disabled — keystore contains no private key entry");
                return;
            }
            this.privateKey = (PrivateKey) ks.getKey(alias, password);
            this.chain = ks.getCertificateChain(alias);
            if (privateKey == null || chain == null || chain.length == 0) {
                log.error("PDF signing disabled — alias '{}' has no usable key/chain", alias);
                this.privateKey = null;
                return;
            }
            this.fingerprint = sha256Fingerprint(chain[0]);
            log.info("PDF signing enabled — certificate SHA-256 {}", fingerprint);
        } catch (Exception e) {
            // Never rethrow: a broken signing config must not stop the
            // application from generating documents.
            log.error("PDF signing disabled — could not load keystore: {}", e.getMessage());
            this.privateKey = null;
            this.chain = null;
            this.fingerprint = null;
        }
    }

    public boolean isEnabled() {
        return privateKey != null && chain != null && chain.length > 0;
    }

    /**
     * SHA-256 fingerprint of the signing certificate, or null when signing is
     * off. Published so a recipient can confirm the "unknown" signature they
     * are looking at is in fact ours.
     */
    public String certificateFingerprint() {
        return fingerprint;
    }

    /**
     * Sign, returning the signed bytes — or the input unchanged when signing is
     * disabled or fails.
     *
     * <p>Returning the original on failure is the important behaviour. A
     * signature is an enhancement to a document that is already correct and
     * already fingerprinted; losing it should cost the signature, not the
     * document. The failure is logged so it cannot pass unnoticed.
     */
    public byte[] sign(byte[] pdf) {
        if (!isEnabled() || pdf == null || pdf.length == 0) return pdf;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(pdf.length + 8192);
            PdfSigner signer = new PdfSigner(
                    new PdfReader(new ByteArrayInputStream(pdf)), out, new StampingProperties());

            // Reason/location live on the appearance object in iText 7.2, not
            // on the signer itself.
            signer.getSignatureAppearance().setReason(reason);
            if (location != null && !location.isBlank()) {
                signer.getSignatureAppearance().setLocation(location);
            }
            // Invisible: the mark a reader should look at is the viewer's own
            // signature panel, not a graphic we drew that anyone could copy.
            signer.setFieldName("CrixaaSignature");

            IExternalSignature signature = new PrivateKeySignature(
                    privateKey, "SHA-256", org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME);

            // CAdES (subfilter ETSI.CAdES.detached) is the PAdES-compatible
            // form; the older CMS subfilter is what non-PAdES tooling emits and
            // is progressively less well accepted.
            signer.signDetached(new BouncyCastleDigest(), signature, chain, null, null, null, 0,
                    PdfSigner.CryptoStandard.CADES);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("PDF signing failed — returning the unsigned document", e);
            return pdf;
        }
    }

    private static String firstKeyAlias(KeyStore ks) throws Exception {
        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (ks.isKeyEntry(alias)) return alias;
        }
        return null;
    }

    private static String sha256Fingerprint(Certificate cert) throws Exception {
        byte[] encoded = ((X509Certificate) cert).getEncoded();
        return HexFormat.ofDelimiter(":").withUpperCase()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
    }
}
