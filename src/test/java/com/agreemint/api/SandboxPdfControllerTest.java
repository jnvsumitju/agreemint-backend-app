package com.agreemint.api;

import com.agreemint.api.dto.PreviewPdfRequest;
import com.agreemint.api.public_.SandboxPdfController;
import com.agreemint.billing.PlanGate;
import com.agreemint.service.DocumentGenerationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The signed-out download in the /try sandbox.
 *
 * <p>The test that matters most here is {@link #anonymousRenderIsAlwaysWatermarked}.
 * {@code PlanGate.isFreeRestricted(null)} returns {@code false} on its first
 * line, so a null org reads as UNRESTRICTED rather than as the most restricted
 * caller there is. Route an anonymous visitor through the ordinary preview path
 * and they receive a cleaner document than a free-plan customer who signed up
 * for one — an inversion that costs nothing to introduce and would be invisible
 * until somebody compared two files.
 *
 * <p>The rest bound cost. The render is synchronous iText work on a request
 * thread shared with paying customers, so an oversized layout must be refused
 * before it reaches the renderer, not after.
 */
class SandboxPdfControllerTest {

    private DocumentGenerationService generation;
    private ProxyManager<String> buckets;
    private BucketProxy bucket;
    private SandboxPdfController controller;
    private HttpServletRequest http;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] PDF = "%PDF-1.7 rendered".getBytes();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        generation = mock(DocumentGenerationService.class);
        buckets = mock(ProxyManager.class);
        bucket = mock(BucketProxy.class);
        http = mock(HttpServletRequest.class);

        RemoteBucketBuilder<String> builder = mock(RemoteBucketBuilder.class);
        when(buckets.builder()).thenReturn(builder);
        when(builder.build(anyString(), any(java.util.function.Supplier.class))).thenReturn(bucket);
        when(bucket.tryConsume(1)).thenReturn(true);
        when(generation.renderAnonymousSandboxPdf(any(), any())).thenReturn(PDF);
        when(http.getRemoteAddr()).thenReturn("203.0.113.9");

        controller = new SandboxPdfController(generation, buckets, 8);
    }

    private static JsonNode layout(int elements) {
        var root = MAPPER.createObjectNode();
        var arr = root.putArray("elements");
        for (int i = 0; i < elements; i++) {
            arr.addObject().put("id", UUID.randomUUID().toString()).put("type", "TEXT");
        }
        return root;
    }

    // ── the inversion this endpoint exists to avoid ───────────────────────────

    @Test
    void anonymousRenderIsAlwaysWatermarked() {
        // The controller must reach for the method that takes no org at all.
        // renderPreviewPdf(layout, data, null) would compile, would return a
        // valid PDF, and would be clean.
        controller.sandboxPdf(new PreviewPdfRequest(layout(1), null), http);

        verify(generation).renderAnonymousSandboxPdf(any(), any());
        verify(generation, never()).renderPreviewPdf(any(), any(), any());
    }

    @Test
    void nullOrgIsNotTreatedAsRestrictedByPlanGate() {
        // Documents the premise above rather than trusting it: if this ever
        // starts returning true, the separate anonymous path is redundant and
        // this test should be the thing that says so.
        PlanGate gate = mock(PlanGate.class);
        when(gate.isFreeRestricted(null)).thenCallRealMethod();
        assertEquals(false, gate.isFreeRestricted(null),
                "isFreeRestricted(null) must stay false, which is exactly why "
                        + "renderAnonymousSandboxPdf hardcodes the watermark");
    }

    // ── cost bounds ──────────────────────────────────────────────────────────

    @Test
    void refusesAnOversizedLayoutWithoutRendering() {
        // 512 KB cap; ~9k elements clears it comfortably.
        var tooBig = layout(9000);
        var e = assertThrows(ResponseStatusException.class,
                () -> controller.sandboxPdf(new PreviewPdfRequest(tooBig, null), http));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, e.getStatusCode());
        // The point of the cap: iText is never entered.
        verify(generation, never()).renderAnonymousSandboxPdf(any(), any());
    }

    @Test
    void refusesWhenTheAddressIsOutOfBudget() {
        when(bucket.tryConsume(1)).thenReturn(false);

        var e = assertThrows(ResponseStatusException.class,
                () -> controller.sandboxPdf(new PreviewPdfRequest(layout(1), null), http));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
        verify(generation, never()).renderAnonymousSandboxPdf(any(), any());
    }

    @Test
    void sizeIsCheckedBeforeBudgetIsSpent() {
        // A huge payload must not burn the visitor's hourly allowance. Someone
        // whose document is too big should still get their free download after
        // trimming it.
        assertThrows(ResponseStatusException.class,
                () -> controller.sandboxPdf(new PreviewPdfRequest(layout(9000), null), http));
        verify(bucket, never()).tryConsume(1);
    }

    @Test
    void rejectsAMissingLayout() {
        var e = assertThrows(ResponseStatusException.class,
                () -> controller.sandboxPdf(new PreviewPdfRequest(null, null), http));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }

    // ── availability ─────────────────────────────────────────────────────────

    @Test
    void failsOpenWhenTheLimiterIsUnavailable() {
        // Redis down must not take the acquisition funnel down with it.
        when(bucket.tryConsume(1)).thenThrow(new IllegalStateException("redis unreachable"));

        assertArrayEquals(PDF, controller.sandboxPdf(new PreviewPdfRequest(layout(1), null), http));
    }

    // ── the flag itself, one level down ──────────────────────────────────────

    /**
     * The controller test above proves only that the right METHOD is called.
     * This proves the method does the thing its name promises — that the
     * watermark boolean actually reaches iText as {@code true}. Without it,
     * changing the literal in {@code renderAnonymousSandboxPdf} to
     * {@code planGate.isFreeRestricted(null)} would keep every other test green
     * while silently shipping clean PDFs to signed-out visitors.
     */
    @Test
    void theServicePassesTheWatermarkFlagToTheRenderer() throws Exception {
        var renderer = mock(com.agreemint.pdf.PdfRendererService.class);
        var versions = mock(com.agreemint.service.TemplateVersionService.class);
        when(renderer.render(any(), any(), eq(true))).thenReturn(PDF);

        var service = new DocumentGenerationService(
                versions,
                mock(com.agreemint.repository.GeneratedDocumentRepository.class),
                mock(com.agreemint.repository.DocumentReceiptRepository.class),
                renderer,
                mock(com.agreemint.pdf.PdfSigningService.class),
                mock(com.agreemint.service.R2StorageService.class),
                mock(com.agreemint.service.WebhookService.class),
                mock(PlanGate.class),
                mock(com.agreemint.billing.PdfQuotaService.class));

        service.renderAnonymousSandboxPdf(layout(1), null);

        verify(renderer).render(any(), any(), eq(true));
        verify(renderer, never()).render(any(), any(), eq(false));
        // And never the unwatermarked two-arg overload.
        verify(renderer, never()).render(any(), any());
    }

    @Test
    void bucketsByForwardedAddressWhenPresent() {
        when(http.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7, 10.0.0.1");

        @SuppressWarnings("unchecked")
        RemoteBucketBuilder<String> builder = mock(RemoteBucketBuilder.class);
        when(buckets.builder()).thenReturn(builder);
        when(builder.build(anyString(), any(java.util.function.Supplier.class))).thenReturn(bucket);

        controller.sandboxPdf(new PreviewPdfRequest(layout(1), null), http);

        // First entry in the chain, not the proxy hop.
        verify(builder).build(eq("sandboxpdf:198.51.100.7:c8"), any(java.util.function.Supplier.class));
    }
}
