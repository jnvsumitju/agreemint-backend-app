package com.agreemint.api.public_;

import com.agreemint.api.dto.PreviewPdfRequest;
import com.agreemint.config.RateLimitConfig;
import com.agreemint.service.DocumentGenerationService;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.Supplier;

/**
 * One free, watermarked PDF for a signed-out visitor in the {@code /try} sandbox.
 *
 * <p><b>Why this exists.</b> Thirty-four of the fifty template pages on
 * crixaa.com end their meta description with "Edit and download free", and the
 * sandbox then asked for a signup before it would produce anything. Someone
 * arriving on a query like "gst invoice template download" hit a wall at the
 * exact moment of intent — the worst place to lose a visitor, and a promise the
 * search result had already made on our behalf.
 *
 * <p><b>Why a new endpoint rather than opening the existing one.</b>
 * {@code POST /api/generate/preview} would already serve an anonymous caller —
 * it tolerates a null principal and needs no template row, org or quota — and
 * only the blanket {@code /api/**} matcher in {@code SecurityConfig} stands in
 * front of it. Moving that matcher would expose the measurement endpoints
 * beside it and, worse, would hand anonymous callers an UNWATERMARKED document:
 * {@code PlanGate.isFreeRestricted(null)} returns false, so a null org is
 * treated as unrestricted rather than as the most restricted caller there is.
 * This endpoint calls {@link DocumentGenerationService#renderAnonymousSandboxPdf}
 * instead, which takes no org and always watermarks.
 *
 * <p><b>What bounds it.</b> A per-IP token bucket, following the same Bucket4j
 * pattern as {@link DocumentVerificationController}. The "one free download" the
 * UI shows is a courtesy counted in the browser; clearing storage or opening a
 * private window defeats it, and it is not pretended otherwise. The rate limit
 * is the actual control, and what it protects is not the templates — those are
 * free and public — but the render threads, which are shared with paying
 * customers. A body cap rejects an oversized layout before it reaches iText.
 *
 * <p>Deliberately NOT defended against at this stage: a distributed harvest
 * from many addresses. The prize is fifty documents that anyone may download
 * one at a time anyway, so the cost of stopping it exceeds the cost of it
 * happening.
 */
@io.swagger.v3.oas.annotations.tags.Tag(
        name = "Sandbox",
        description = "Anonymous template trial")
@RestController
@RequestMapping("/api/public")
public class SandboxPdfController {

    private static final Logger log = LoggerFactory.getLogger(SandboxPdfController.class);

    /**
     * Largest layout an anonymous caller may submit, serialised.
     *
     * <p>Every one of the fifty shipped templates is comfortably under 100 KB.
     * The cap is not there to fit them; it is there so a hand-built payload
     * cannot arrive with thousands of elements and turn one HTTP request into
     * minutes of iText work on a thread a customer is waiting for.
     */
    private static final int MAX_LAYOUT_CHARS = 512 * 1024;

    private final DocumentGenerationService generation;
    private final ProxyManager<String> buckets;
    private final int perIpPerHour;

    public SandboxPdfController(
            DocumentGenerationService generation,
            ProxyManager<String> buckets,
            @Value("${agreemint.sandbox.pdf-per-ip-per-hour:8}") int perIpPerHour) {
        this.generation = generation;
        this.buckets = buckets;
        this.perIpPerHour = perIpPerHour;
    }

    @PostMapping(
            value = "/sandbox/pdf",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public byte[] sandboxPdf(@RequestBody PreviewPdfRequest request, HttpServletRequest http) {
        String ip = clientIp(http);

        if (request == null || request.layout() == null || request.layout().isNull()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "layout is required");
        }
        if (request.layout().toString().length() > MAX_LAYOUT_CHARS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "This document is too large for the free preview. Create a free account to"
                            + " generate documents this size.");
        }
        if (!consumeToken(ip)) {
            // 429 with a human sentence: the console shows this verbatim, and
            // the reader is a person who came for a document, not an API client.
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "You have used the free downloads available from this network for now."
                            + " Create a free account to keep generating documents.");
        }

        byte[] pdf = generation.renderAnonymousSandboxPdf(request.layout(), request.data());
        // The only record that this happened. No row is written: an anonymous
        // download is not a document anyone can be shown later, and a table
        // indexed by IP would be a privacy liability for no operational gain.
        log.info("Anonymous sandbox PDF rendered ({} bytes) for ip={}", pdf.length, ip);
        return pdf;
    }

    /**
     * True when this address still has budget.
     *
     * <p>Fails OPEN, matching {@link DocumentVerificationController}. If Redis
     * is unreachable the visitor still gets their document; the limiter exists
     * to bound cost, and taking the funnel down to protect CPU that is probably
     * idle would be the more expensive failure.
     */
    private boolean consumeToken(String ip) {
        try {
            BucketConfiguration config = RateLimitConfig.perIpAnonymousPdf(perIpPerHour);
            Supplier<BucketConfiguration> supplier = () -> config;
            return buckets.builder()
                    .build("sandboxpdf:" + ip + RateLimitConfig.capacitySuffix(perIpPerHour), supplier)
                    .tryConsume(1);
        } catch (RuntimeException e) {
            log.warn("Sandbox PDF rate limiter unavailable, allowing request", e);
            return true;
        }
    }

    /**
     * Best-effort client address.
     *
     * <p>{@code X-Forwarded-For} is client-controlled and trivially spoofed, so
     * this spreads honest traffic across buckets rather than establishing an
     * identity. Anyone willing to forge the header is not being stopped by this,
     * and is not who the limit is for.
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
}
