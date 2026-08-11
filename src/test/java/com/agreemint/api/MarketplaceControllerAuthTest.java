package com.agreemint.api;

import com.agreemint.billing.PlanGate;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.Template;
import com.agreemint.security.OrgAuthorizationService;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.MarketplaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Constructor;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Authorization cover for the marketplace write routes.
 *
 * <p>These exist because of a specific hole: {@code POST /api/marketplace} took
 * {@code sourceTemplateId} from the request body and stored it without ever
 * asking whether the caller could see that template. Any Starter user could
 * publish another workspace's template as their own listing and then clone it
 * back — a cross-tenant read of customer content through two ordinary calls. It
 * was invisible because the console has no publish button, so nothing in the
 * product exercised the route.
 *
 * <p>The check lives in the controller, matching how every other template write
 * is gated, so it has to be tested here — {@code MarketplaceServiceTest} passes
 * either way.
 */
class MarketplaceControllerAuthTest {

    private MarketplaceService service;
    private PlanGate planGate;
    private OrgAuthorizationService orgAuthz;
    private MarketplaceController controller;

    private final UUID callerId = UUID.randomUUID();
    private final UUID callerOrgId = UUID.randomUUID();
    /** A template in a workspace the caller has nothing to do with. */
    private final UUID foreignTemplateId = UUID.randomUUID();

    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        service = mock(MarketplaceService.class);
        planGate = mock(PlanGate.class);
        orgAuthz = mock(OrgAuthorizationService.class);
        controller = new MarketplaceController(service, planGate, orgAuthz);
        principal = principal(callerId, callerOrgId);
    }

    /** UserPrincipal's shape varies; build it reflectively so this test does not pin it. */
    private static UserPrincipal principal(UUID userId, UUID orgId) {
        for (Constructor<?> c : UserPrincipal.class.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            Object[] args = new Object[p.length];
            for (int i = 0; i < p.length; i++) {
                if (p[i] == UUID.class) args[i] = (i == 0) ? userId : orgId;
                else if (p[i] == String.class) args[i] = "user@example.test";
                else if (p[i] == boolean.class) args[i] = false;
                else args[i] = null;
            }
            try {
                c.setAccessible(true);
                return (UserPrincipal) c.newInstance(args);
            } catch (Exception ignored) {
                // try the next constructor
            }
        }
        throw new IllegalStateException("could not construct UserPrincipal");
    }

    private MarketplaceController.PublishRequest publishReq(UUID sourceTemplateId) {
        return new MarketplaceController.PublishRequest(
                "Stolen invoice", "desc", "Mallory", sourceTemplateId, "Finance", "tag");
    }

    // ── publish ───────────────────────────────────────────────────────────

    @Test
    void publishingSomeoneElsesTemplateIsRefused() {
        // assertTemplateAccess resolves the template's own org and checks
        // membership there, so a caller outside that org is forbidden.
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this organization"))
                .when(orgAuthz).assertTemplateAccess(eq(callerId), eq(foreignTemplateId), any(OrgRole[].class));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.publish(principal, publishReq(foreignTemplateId)));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(service, never()).publish(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void publishChecksAccessBeforeTouchingTheService() {
        controller.publish(principal, publishReq(foreignTemplateId));

        InOrder order = inOrder(orgAuthz, service);
        order.verify(orgAuthz).assertTemplateAccess(eq(callerId), eq(foreignTemplateId), any(OrgRole[].class));
        order.verify(service).publish(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void publishRequiresAWriteRoleNotJustMembership() {
        controller.publish(principal, publishReq(foreignTemplateId));

        ArgumentCaptor<OrgRole[]> roles = ArgumentCaptor.forClass(OrgRole[].class);
        verify(orgAuthz).assertTemplateAccess(eq(callerId), eq(foreignTemplateId), roles.capture());

        // A VIEWER must not be able to republish the workspace's templates.
        assertFalse(java.util.Arrays.asList(roles.getValue()).contains(OrgRole.VIEWER));
        assertTrue(java.util.Arrays.asList(roles.getValue()).contains(OrgRole.ADMIN));
    }

    @Test
    void publishWithoutASourceTemplateIsRejected() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.publish(principal, publishReq(null)));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verifyNoInteractions(service);
    }

    // ── clone ─────────────────────────────────────────────────────────────

    @Test
    void installingRequiresPermissionToCreateTemplates() {
        UUID listingId = UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient permissions"))
                .when(orgAuthz).assertRole(eq(callerId), eq(callerOrgId), any(OrgRole[].class));

        assertThrows(ResponseStatusException.class, () -> controller.clone(principal, listingId));

        // A VIEWER cannot create a template through any other route; installing
        // one must not be the exception.
        verify(service, never()).cloneTemplate(any(), any(), any());
    }

    @Test
    void installGoesAheadForADesigner() {
        UUID listingId = UUID.randomUUID();
        Template cloned = new Template();
        cloned.setId(UUID.randomUUID());
        cloned.setName("Invoice");
        when(service.cloneTemplate(listingId, callerOrgId, callerId)).thenReturn(cloned);

        var response = controller.clone(principal, listingId);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(orgAuthz).assertRole(eq(callerId), eq(callerOrgId), any(OrgRole[].class));
    }
}
