package com.agreemint.billing;

import com.agreemint.api.BadRequestException;
import com.agreemint.config.RazorpayProperties;
import com.agreemint.domain.BillingPeriod;
import com.agreemint.domain.OrgPlan;
import com.agreemint.domain.Organization;
import com.agreemint.domain.Subscription;
import com.agreemint.domain.SubscriptionStatus;
import com.agreemint.repository.OrganizationRepository;
import com.agreemint.repository.SubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Starting a new checkout when a previous one was abandoned.
 *
 * <p>A subscription sitting in {@code CREATED} was never authenticated: the
 * customer opened checkout and walked away, so no mandate exists and nothing can
 * be charged. It nevertheless occupied the org's single subscription slot, and
 * the console treated {@code CREATED} as "not live" — so it offered the upgrade
 * cards the server then refused, and hid the cancel button that would have
 * cleared the row. A workspace that abandoned one checkout could never buy
 * anything again, through any route in the product.
 */
class AbandonedCheckoutTest {

    private SubscriptionRepository subRepo;
    private OrganizationRepository orgRepo;
    private RazorpayClient razorpay;
    private BillingService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final List<Subscription> saved = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        subRepo = mock(SubscriptionRepository.class);
        orgRepo = mock(OrganizationRepository.class);
        razorpay = mock(RazorpayClient.class);
        saved.clear();

        Organization org = new Organization();
        org.setId(orgId);
        org.setName("Acme");
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(org));

        when(subRepo.saveAndFlush(any())).thenAnswer((i) -> {
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });

        RazorpayProperties props = new RazorpayProperties();
        props.setKeyId("rzp_test_key");
        props.setPlanStarterMonthly("plan_starter_monthly");

        when(razorpay.createSubscription(any(), anyInt(), any())).thenReturn(
                new ObjectMapper().readTree(
                        "{\"id\":\"sub_NEW\",\"status\":\"created\",\"customer_id\":\"cust_1\"}"));

        service = new BillingService(razorpay, props, subRepo,
                mock(com.agreemint.repository.BillingEventRepository.class), orgRepo,
                mock(OrgEntitlementService.class), mock(ApiAccessGraceService.class));
    }

    private Subscription abandoned() {
        Subscription s = new Subscription();
        s.setId(UUID.randomUUID());
        s.setOrgId(orgId);
        s.setRazorpaySubscriptionId("sub_OLD");
        s.setStatus(SubscriptionStatus.CREATED);
        when(subRepo.findFirstByOrgIdAndStatusInOrderByCreatedAtDesc(eq(orgId), any()))
                .thenReturn(Optional.of(s));
        return s;
    }

    private Subscription live(SubscriptionStatus status) {
        Subscription s = new Subscription();
        s.setId(UUID.randomUUID());
        s.setOrgId(orgId);
        s.setRazorpaySubscriptionId("sub_LIVE");
        s.setStatus(status);
        when(subRepo.findFirstByOrgIdAndStatusInOrderByCreatedAtDesc(eq(orgId), any()))
                .thenReturn(Optional.of(s));
        return s;
    }

    private void remoteStatus(String status) throws Exception {
        when(razorpay.fetchSubscription("sub_OLD")).thenReturn(
                new ObjectMapper().readTree("{\"id\":\"sub_OLD\",\"status\":\"" + status + "\"}"));
    }

    private Subscription startCheckout() {
        return service.createSubscription(orgId, OrgPlan.STARTER, BillingPeriod.MONTHLY, actorId);
    }

    // ── the deadlock ──────────────────────────────────────────────────────

    @Test
    void anAbandonedCheckoutNoLongerBlocksANewOne() {
        abandoned();
        assertDoesNotThrow(() -> remoteStatus("created"));

        Subscription created = startCheckout();

        assertEquals("sub_NEW", created.getRazorpaySubscriptionId());
    }

    @Test
    void theAbandonedOneIsCancelledAtRazorpay() throws Exception {
        // Not optional. The abandoned checkout's payment link stays live and
        // payable — if we only retired our own row, a customer opening that old
        // link later would activate a subscription we had written off, leaving
        // two mandates on one workspace.
        abandoned();
        remoteStatus("created");

        startCheckout();

        verify(razorpay).cancelSubscription("sub_OLD", false);
    }

    @Test
    void theAbandonedRowIsRetiredLocallyToo() throws Exception {
        Subscription old = abandoned();
        remoteStatus("created");

        startCheckout();

        // Otherwise the partial unique index still refuses the new row.
        assertEquals(SubscriptionStatus.CANCELLED, old.getStatus());
    }

    @Test
    void ifRazorpayWillNotCancelWeRefuseRatherThanRiskADoubleCharge() throws Exception {
        abandoned();
        remoteStatus("created");
        doThrow(new RuntimeException("razorpay down"))
                .when(razorpay).cancelSubscription(anyString(), anyBoolean());

        assertThrows(BadRequestException.class, this::startCheckout);
        verify(razorpay, never()).createSubscription(any(), anyInt(), any());
    }

    @Test
    void aSubscriptionAlreadyDeadAtRazorpayIsJustReconciled() throws Exception {
        // Our row drifted — we missed the cancellation webhook. Nothing to
        // cancel remotely, so do not call it and do not fail.
        Subscription old = abandoned();
        remoteStatus("cancelled");

        startCheckout();

        verify(razorpay, never()).cancelSubscription(anyString(), anyBoolean());
        assertEquals(SubscriptionStatus.CANCELLED, old.getStatus());
    }

    // ── discarding a pending checkout ─────────────────────────────────────

    @Test
    void discardingAPendingCheckoutIsAlwaysImmediate() throws Exception {
        // The console's normal cancel asks for end-of-period. An unpaid
        // subscription has no period to end, and Razorpay rejects
        // cancel_at_cycle_end on one, so the server must override the request.
        Subscription old = abandoned();
        when(razorpay.cancelSubscription(anyString(), anyBoolean())).thenReturn(
                new ObjectMapper().readTree("{\"id\":\"sub_OLD\",\"status\":\"cancelled\"}"));

        service.cancel(orgId, false);

        verify(razorpay).cancelSubscription("sub_OLD", false);
        assertFalse(old.isCancelAtPeriodEnd());
        assertNotNull(old.getCancelledAt());
    }

    @Test
    void discardingAPendingCheckoutDoesNotDowngradeAnything() throws Exception {
        // It never granted access, so there is nothing to take away — and a
        // spurious downgrade would clear entitlements the org may hold for
        // another reason.
        abandoned();
        when(razorpay.cancelSubscription(anyString(), anyBoolean())).thenReturn(
                new ObjectMapper().readTree("{\"id\":\"sub_OLD\",\"status\":\"cancelled\"}"));

        service.cancel(orgId, false);

        verify(orgRepo, never()).save(any());
    }

    @Test
    void cancellingARealSubscriptionStillHonoursEndOfPeriod() throws Exception {
        Subscription real = live(SubscriptionStatus.ACTIVE);
        when(razorpay.cancelSubscription(anyString(), anyBoolean())).thenReturn(
                new ObjectMapper().readTree("{\"id\":\"sub_LIVE\",\"status\":\"active\"}"));

        service.cancel(orgId, false);

        verify(razorpay).cancelSubscription("sub_LIVE", true);
        assertTrue(real.isCancelAtPeriodEnd());
    }

    // ── the case the guard exists for ─────────────────────────────────────

    @Test
    void aHaltedSubscriptionDoesNotBlockRecovery() throws Exception {
        // Retries exhausted: the org is already back on FREE and the mandate
        // will never charge again. Blocking here left a paying customer with no
        // self-serve way back — the console's own banner told them to start a
        // new subscription that the server then refused.
        Subscription halted = live(SubscriptionStatus.HALTED);
        when(razorpay.fetchSubscription("sub_LIVE")).thenReturn(
                new ObjectMapper().readTree("{\"id\":\"sub_LIVE\",\"status\":\"halted\"}"));

        Subscription created = startCheckout();

        assertEquals("sub_NEW", created.getRazorpaySubscriptionId());
        assertEquals(SubscriptionStatus.CANCELLED, halted.getStatus());
    }

    @Test
    void cancellingAHaltedSubscriptionIsImmediateNotEndOfPeriod() throws Exception {
        // HALTED grants no access, so there is no period left to run out and
        // Razorpay rejects cancel_at_cycle_end on it. Deferring also left the
        // row occupying the slot, so the customer still could not buy anything.
        Subscription halted = live(SubscriptionStatus.HALTED);
        when(razorpay.cancelSubscription(anyString(), anyBoolean())).thenReturn(
                new ObjectMapper().readTree("{\"id\":\"sub_LIVE\",\"status\":\"cancelled\"}"));

        service.cancel(orgId, false);

        verify(razorpay).cancelSubscription("sub_LIVE", false);
        assertFalse(halted.isCancelAtPeriodEnd());
    }

    @Test
    void arealMandateStillBlocksASecondCheckout() {
        for (SubscriptionStatus s : List.of(SubscriptionStatus.ACTIVE,
                SubscriptionStatus.AUTHENTICATED, SubscriptionStatus.PENDING)) {
            live(s);
            BadRequestException e = assertThrows(BadRequestException.class, this::startCheckout,
                    "status " + s + " must still block");
            assertTrue(e.getMessage().contains("already has a subscription"));
        }
    }

    @Test
    void blockingAMandateNeverTouchesRazorpay() throws Exception {
        live(SubscriptionStatus.ACTIVE);

        assertThrows(BadRequestException.class, this::startCheckout);

        // An active mandate must not be cancelled as a side effect of someone
        // clicking Upgrade again.
        verify(razorpay, never()).cancelSubscription(anyString(), anyBoolean());
        verify(razorpay, never()).createSubscription(any(), anyInt(), any());
    }
}
