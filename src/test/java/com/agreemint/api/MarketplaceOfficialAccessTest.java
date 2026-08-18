package com.agreemint.api;

import com.agreemint.billing.PlanGate;
import com.agreemint.domain.OrgPlan;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.Template;
import com.agreemint.security.OrgAuthorizationService;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.MarketplaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * First-party listings are free on every plan; everything else stays Starter+.
 *
 * <p>The whole marketplace used to sit behind {@code requireAtLeast(STARTER)},
 * including the browse route. Shipping twenty free Crixaa templates behind that
 * gate would have hidden them from FREE-plan orgs — the exact audience they
 * exist for — so browse now degrades to official-only instead of refusing.
 *
 * <p>The risk in "degrade instead of 403" is leaking the rest of the
 * marketplace, so the important assertions here are the negative ones: a
 * FREE-plan caller must not receive third-party rows, and must still be refused
 * a third-party listing by id. The exemption keys off the listing's own stored
 * flag, never off anything in the request.
 */
class MarketplaceOfficialAccessTest {

    private MarketplaceService service;
    private PlanGate planGate;
    private OrgAuthorizationService orgAuthz;
    private MarketplaceController controller;

    private final UUID callerId = UUID.randomUUID();
    private final UUID freeOrgId = UUID.randomUUID();
    private final UUID officialListingId = UUID.randomUUID();
    private final UUID thirdPartyListingId = UUID.randomUUID();

    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        service = mock(MarketplaceService.class);
        planGate = mock(PlanGate.class);
        orgAuthz = mock(OrgAuthorizationService.class);
        controller = new MarketplaceController(service, planGate, orgAuthz);
        principal = new UserPrincipal(callerId, "free@example.com", freeOrgId, OrgRole.ADMIN);

        when(service.isOfficial(officialListingId)).thenReturn(true);
        when(service.isOfficial(thirdPartyListingId)).thenReturn(false);
    }

    /** A FREE-plan org: below the marketplace plan. */
    private void onFreePlan() {
        when(planGate.hasAtLeast(eq(freeOrgId), any(OrgPlan.class))).thenReturn(false);
        doThrow(new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "upgrade"))
                .when(planGate).requireAtLeast(eq(freeOrgId), any(OrgPlan.class), anyString());
    }

    private void onPaidPlan() {
        when(planGate.hasAtLeast(eq(freeOrgId), any(OrgPlan.class))).thenReturn(true);
    }

    @Test
    void freePlanBrowsesOfficialListingsOnly() {
        onFreePlan();
        controller.list(principal, null);
        // The restriction must be applied in the query — a caller who may not
        // see third-party rows must not have them loaded and filtered after.
        verify(service).listPublished(true);
        verify(service, never()).listPublished(false);
    }

    @Test
    void paidPlanBrowsesEverything() {
        onPaidPlan();
        controller.list(principal, null);
        verify(service).listPublished(false);
    }

    @Test
    void categoryBrowseCarriesTheSameRestriction() {
        onFreePlan();
        controller.list(principal, "Finance");
        verify(service).listByCategory("Finance", true);
        verify(service, never()).listByCategory("Finance", false);
    }

    @Test
    void freePlanMayOpenAnOfficialListing() {
        onFreePlan();
        controller.get(principal, officialListingId);
        verify(service).getById(officialListingId);
        verify(planGate, never()).requireAtLeast(any(), any(), anyString());
    }

    @Test
    void freePlanIsStillRefusedAThirdPartyListing() {
        onFreePlan();
        assertThrows(ResponseStatusException.class,
                () -> controller.get(principal, thirdPartyListingId),
                "the exemption must not extend past first-party rows");
        verify(service, never()).getById(thirdPartyListingId);
    }

    @Test
    void freePlanMayInstallAnOfficialListing() {
        onFreePlan();
        Template cloned = new Template();
        cloned.setId(UUID.randomUUID());
        cloned.setName("GST Invoice");
        when(service.cloneTemplate(eq(officialListingId), eq(freeOrgId), eq(callerId)))
                .thenReturn(cloned);

        var response = controller.clone(principal, officialListingId);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    void installingAnOfficialListingStillRequiresAWriteRole() {
        // The exemption changes which PLANS may install, never which ROLES. A
        // VIEWER cannot create a template through any other route and must not
        // gain one here just because the listing is free.
        onFreePlan();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "role"))
                .when(orgAuthz).assertRole(eq(callerId), eq(freeOrgId),
                        eq(OrgRole.ADMIN), eq(OrgRole.DESIGNER));

        assertThrows(ResponseStatusException.class,
                () -> controller.clone(principal, officialListingId));
        verify(service, never()).cloneTemplate(any(), any(), any());
    }

    @Test
    void freePlanIsStillRefusedInstallingAThirdPartyListing() {
        onFreePlan();
        assertThrows(ResponseStatusException.class,
                () -> controller.clone(principal, thirdPartyListingId));
        verify(service, never()).cloneTemplate(any(), any(), any());
    }
}
