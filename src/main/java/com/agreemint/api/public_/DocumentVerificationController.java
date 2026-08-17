package com.agreemint.api.public_;

import com.agreemint.config.RateLimitConfig;
import com.agreemint.domain.DocumentReceipt;
import com.agreemint.pdf.PdfSigningService;
import com.agreemint.repository.DocumentReceiptRepository;
import com.agreemint.service.VerificationCodes;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Public, unauthenticated document verification.
 *
 * <p>Answers one question: did this platform issue a PDF with exactly these
 * bytes? The caller sends a SHA-256; we look it up among the receipts written at
 * generation. Any modification to a document — an edited figure, a swapped page,
 * a changed name — yields a different digest and finds nothing, including
 * changes that are invisible on screen.
 *
 * <p><b>The file is never uploaded.</b> The verification page hashes it in the
 * browser with {@code crypto.subtle.digest} and sends 64 hex characters. That is
 * deliberate and worth preserving: a recipient checking a contract should not
 * have to send it to a third party to find out whether it is genuine, and we
 * should not want a copy.
 *
 * <p><b>Keyed on the digest, never the document id.</b> A document UUID is not a
 * secret — it appears in the file URL, the storage key, the download filename
 * and the {@code document.generated} webhook — so accepting one here would turn
 * a public endpoint into a lookup for anyone who has seen an id in a log. A
 * digest can only be produced by someone holding the file.
 *
 * <p><b>What comes back is deliberately thin.</b> Whether it matched, and when
 * it was issued. No organisation, no template name, no input data, and never the
 * document itself. The person asking already holds the file; they need to know
 * if it is intact, not to be told anything new about the issuer.
 */
@RestController
@RequestMapping("/api/public")
public class DocumentVerificationController {

    /** Lowercase hex SHA-256. Anything else is rejected before touching the database. */
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    private final DocumentReceiptRepository receipts;
    private final ProxyManager<String> buckets;
    private final PdfSigningService signing;
    private final int perMinute;

    public DocumentVerificationController(
            DocumentReceiptRepository receipts,
            ProxyManager<String> buckets,
            PdfSigningService signing,
            @Value("${agreemint.verify.per-ip-per-minute:30}") int perMinute) {
        this.receipts = receipts;
        this.buckets = buckets;
        this.signing = signing;
        this.perMinute = perMinute;
    }

    @PostMapping("/verify")
    public ResponseEntity<VerifyResponse> verify(
            @RequestBody VerifyRequest request,
            HttpServletRequest http) {

        if (!consumeToken(clientIp(http))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        String digest = request == null || request.sha256() == null
                ? ""
                : request.sha256().trim().toLowerCase(Locale.ROOT);

        // A malformed digest is answered exactly like an unknown one. Telling
        // the caller their input was the wrong shape is harmless here, but
        // keeping one response shape means the endpoint has nothing to say
        // beyond match / no match.
        if (!SHA256_HEX.matcher(digest).matches()) {
            return ResponseEntity.ok(VerifyResponse.noMatch());
        }

        List<DocumentReceipt> found = receipts.findBySha256OrderByIssuedAtAsc(digest);
        if (found.isEmpty()) {
            return ResponseEntity.ok(VerifyResponse.noMatch());
        }
        // Ordered by issuance, so the earliest wins. Two rows would mean two
        // byte-identical PDFs; the honest answer is still "yes, we issued this".
        DocumentReceipt receipt = found.get(0);
        return ResponseEntity.ok(new VerifyResponse(true, receipt.getIssuedAt(), receipt.getByteSize()));
    }

    /**
     * True when the caller has budget left.
     *
     * <p>Fails open. If Redis is unreachable, verification keeps working rather
     * than reporting that a genuine document cannot be checked — the limiter is
     * here to bound cost, and a rate limiter that takes the feature down with it
     * is worse than the traffic it was guarding against.
     */
    private boolean consumeToken(String ip) {
        try {
            BucketConfiguration config = RateLimitConfig.perIpVerify(perMinute);
            Supplier<BucketConfiguration> supplier = () -> config;
            return buckets.builder()
                    .build("verify:" + ip + RateLimitConfig.capacitySuffix(perMinute), supplier)
                    .tryConsume(1);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Best-effort client address.
     *
     * <p>{@code X-Forwarded-For} is client-controlled and trivially spoofed, so
     * this is a way to spread honest traffic across buckets, not an identity.
     * The first entry is taken because that is where the proxy chain puts the
     * originating client.
     */
    private static String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) return first;
        }
        String remote = http.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    /**
     * Look up a document by the code printed on it.
     *
     * <p>For someone holding a <em>printout</em>, who has no bytes to hash.
     * That makes it unavoidably an existence oracle, which is why the code is
     * 75 random bits rather than anything derived: guessing one is infeasible,
     * and knowing a document id does not help.
     *
     * <p>It answers strictly less than the digest route. A code confirms a
     * document with that identity was issued and when; it cannot confirm the
     * paper in your hand matches it, because paper has no digest. The wording
     * on the page has to carry that distinction, and it does.
     */
    @GetMapping("/verify/code/{code}")
    public ResponseEntity<CodeResponse> verifyCode(
            @PathVariable String code,
            HttpServletRequest http) {

        if (!consumeToken(clientIp(http))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        String normalised = VerificationCodes.normalise(code);
        if (normalised == null) {
            return ResponseEntity.ok(CodeResponse.noMatch());
        }
        return ResponseEntity.ok(receipts.findByVerificationCode(normalised)
                .map(r -> new CodeResponse(true, normalised, r.getIssuedAt()))
                .orElseGet(CodeResponse::noMatch));
    }

    /**
     * What the signing certificate is, so a recipient looking at a
     * "validity unknown" warning in their PDF reader can confirm the
     * certificate is ours rather than someone else's.
     */
    @GetMapping("/signing-certificate")
    public SigningCertificateResponse signingCertificate() {
        return new SigningCertificateResponse(signing.isEnabled(), signing.certificateFingerprint());
    }

    public record VerifyRequest(String sha256) {}

    public record CodeResponse(boolean found, String code, Instant issuedAt) {
        static CodeResponse noMatch() {
            return new CodeResponse(false, null, null);
        }
    }

    public record SigningCertificateResponse(boolean enabled, String sha256Fingerprint) {}

    /**
     * @param verified   whether a document with these exact bytes was issued
     * @param issuedAt   when, if it matched; null otherwise
     * @param byteSize   size of the issued file, if it matched; null otherwise
     */
    public record VerifyResponse(boolean verified, Instant issuedAt, Long byteSize) {
        static VerifyResponse noMatch() {
            return new VerifyResponse(false, null, null);
        }
    }
}
