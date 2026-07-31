package com.agreemint.billing;

import com.agreemint.domain.OrgPlan;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Cover for the document cap, which until now was a column staff could set
 * that changed nothing. These tests pin the decisions that make it safe to
 * expose in the admin portal: uncapped orgs are untouched, a spent allowance is
 * a 429, zero means zero rather than one, a changed cap reaches an existing
 * bucket, a failed generation is refunded, the bucket is scoped per org, and
 * Redis being down never stops document generation.
 */
class PdfQuotaServiceTest {

    private OrgEntitlementService entitlements;
    private ProxyManager<String> proxyManager;
    private RemoteBucketBuilder<String> builder;
    private BucketProxy bucket;
    private PdfQuotaService service;

    private final UUID orgId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        entitlements = mock(OrgEntitlementService.class);
        proxyManager = mock(ProxyManager.class);
        builder = mock(RemoteBucketBuilder.class);
        bucket = mock(BucketProxy.class);

        when(proxyManager.builder()).thenReturn(builder);
        when(builder.build(any(String.class), any(io.github.bucket4j.BucketConfiguration.class)))
                .thenReturn(bucket);

        service = new PdfQuotaService(entitlements, proxyManager);
    }

    private void entitled(Integer pdfDailyMax) {
        when(entitlements.resolve(orgId)).thenReturn(new OrgEntitlementService.Entitlement(
                OrgPlan.FREE, null, pdfDailyMax, null, false, null));
    }

    @Test
    void aNullOrgIsNotCharged() {
        service.requireHeadroom(null);
        verifyNoInteractions(proxyManager);
        verifyNoInteractions(entitlements);
    }

    @Test
    void anUncappedOrgNeverTouchesTheBucket() {
        entitled(null);

        service.requireHeadroom(orgId);

        // The important half of the guarantee: shipping this must not start
        // throttling orgs that had no cap configured.
        verifyNoInteractions(proxyManager);
    }

    @Test
    void aCappedOrgWithHeadroomIsAllowed() {
        entitled(50);
        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(ConsumptionProbe.consumed(49, 0));

        assertDoesNotThrow(() -> service.requireHeadroom(orgId));
        verify(bucket).tryConsumeAndReturnRemaining(1);
    }

    @Test
    void aSpentAllowanceIsRejectedWith429() {
        entitled(50);
        when(bucket.tryConsumeAndReturnRemaining(1))
                .thenReturn(ConsumptionProbe.rejected(0, 3_600_000_000_000L, 3_600_000_000_000L));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.requireHeadroom(orgId));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.valueOf(e.getStatusCode().value()));
        assertTrue(e.getReason().contains("50"), "the operator-set number belongs in the message");
    }

    @Test
    void theBucketIsScopedToTheOrg() {
        entitled(10);
        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(ConsumptionProbe.consumed(9, 0));

        service.requireHeadroom(orgId);

        // Sharing a key with the per-org API cap would let API traffic eat the
        // document allowance, and vice versa. The :c suffix is what makes a
        // changed cap reach an existing bucket instead of being ignored for days.
        verify(builder).build(eq("pdf:org:" + orgId + ":c10"),
                any(io.github.bucket4j.BucketConfiguration.class));
    }

    @Test
    void redisFailureFailsOpen() {
        entitled(10);
        when(bucket.tryConsumeAndReturnRemaining(1)).thenThrow(new IllegalStateException("redis down"));

        assertDoesNotThrow(() -> service.requireHeadroom(orgId),
                "a limiter outage must not stop every customer generating documents");
    }

    @Test
    void remainingIsNullWhenUncapped() {
        entitled(null);
        assertNull(service.remainingToday(orgId));
        assertNull(service.remainingToday(null));
    }

    @Test
    void remainingReportsAvailableTokens() {
        entitled(10);
        when(bucket.getAvailableTokens()).thenReturn(7L);
        assertEquals(7L, service.remainingToday(orgId));
    }

    @Test
    void aChangedCapUsesADifferentBucket() {
        // Bucket4j ignores the configuration passed for a key that already
        // exists, so reusing one key across caps would enforce whichever cap was
        // seen first — for as long as the Redis key survives.
        entitled(10);
        when(bucket.tryConsumeAndReturnRemaining(1)).thenReturn(ConsumptionProbe.consumed(9, 0));
        service.requireHeadroom(orgId);

        entitled(1000);
        service.requireHeadroom(orgId);

        verify(builder).build(eq("pdf:org:" + orgId + ":c10"), any(io.github.bucket4j.BucketConfiguration.class));
        verify(builder).build(eq("pdf:org:" + orgId + ":c1000"), any(io.github.bucket4j.BucketConfiguration.class));
    }

    @Test
    void zeroMeansZeroRatherThanOne() {
        entitled(0);

        // Bucket4j clamps capacity to at least 1, so a zero cap has to be
        // short-circuited or it silently allows one document a day.
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.requireHeadroom(orgId));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.valueOf(e.getStatusCode().value()));
        verifyNoInteractions(proxyManager);
        assertEquals(0L, service.remainingToday(orgId));
    }

    @Test
    void refundReturnsTheReservedDocument() {
        entitled(10);
        service.refund(orgId);
        verify(bucket).addTokens(1);
    }

    @Test
    void refundIsANoOpWhenUncappedOrZero() {
        entitled(null);
        service.refund(orgId);
        entitled(0);
        service.refund(orgId);
        verifyNoInteractions(proxyManager);
    }

    @Test
    void refundSurvivesARedisFailure() {
        entitled(10);
        doThrow(new IllegalStateException("redis down")).when(bucket).addTokens(1);
        assertDoesNotThrow(() -> service.refund(orgId),
                "a lost refund costs one document; it must not fail the request");
    }

    @Test
    void remainingSurvivesARedisFailure() {
        entitled(10);
        when(bucket.getAvailableTokens()).thenThrow(new IllegalStateException("redis down"));
        assertNull(service.remainingToday(orgId), "reported as unknown, not as zero");
    }
}
