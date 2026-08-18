package com.agreemint.api;

import com.agreemint.api.dto.TemplateResponse;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.TemplateStatus;
import com.agreemint.security.OrgAuthorizationService;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.TemplateDraftService;
import com.agreemint.service.TemplateService;
import com.agreemint.service.TemplateVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who may put a template into use.
 *
 * <p>Status is gated on ADMIN and REVIEWER, deliberately not on the roles that
 * can edit a template. Activating one is what permits documents to be generated
 * from it, which is an approval rather than an edit — so the person who builds a
 * template does not sign their own work off.
 *
 * <p>The consequence is easy to get backwards in a refactor, hence this test: a
 * DESIGNER can change every pixel of a template and still must not be able to
 * activate it.
 */
class TemplateStatusAuthTest {

    private TemplateService templateService;
    private OrgAuthorizationService orgAuthz;
    private TemplateController controller;

    private final UUID callerId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        templateService = mock(TemplateService.class);
        orgAuthz = mock(OrgAuthorizationService.class);
        controller = new TemplateController(
                templateService,
                mock(TemplateVersionService.class),
                mock(TemplateDraftService.class),
                orgAuthz,
                mock(com.agreemint.billing.PlanGate.class));
        principal = new UserPrincipal(callerId, "someone@example.com", orgId, OrgRole.ADMIN);
        when(templateService.setStatus(any(), any())).thenReturn(
                new TemplateResponse(templateId, "T", null, null, null, null,
                        TemplateStatus.ACTIVE, 1, false));
    }

    @Test
    void onlyAdminAndReviewerAreAccepted() {
        controller.setStatus(principal, templateId,
                new TemplateController.SetStatusRequest(TemplateStatus.ACTIVE));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<OrgRole> roles = ArgumentCaptor.forClass(OrgRole.class);
        verify(orgAuthz).assertTemplateAccess(eq(callerId), eq(templateId), roles.capture(), roles.capture());

        List<OrgRole> allowed = roles.getAllValues();
        assertTrue(allowed.contains(OrgRole.ADMIN), "an admin must be able to activate");
        assertTrue(allowed.contains(OrgRole.REVIEWER), "a reviewer signs templates off");
        // The one that matters: building a template is not approving it.
        assertFalse(allowed.contains(OrgRole.DESIGNER),
                "a DESIGNER must not be able to put their own template into use");
        assertFalse(allowed.contains(OrgRole.VIEWER));
    }

    @Test
    void authorizationRunsBeforeAnythingIsWritten() {
        // A refused caller must not reach the service at all — checking after
        // the write would still change the row.
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "nope"))
                .when(orgAuthz).assertTemplateAccess(any(), any(), any(), any());

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.setStatus(principal, templateId,
                        new TemplateController.SetStatusRequest(TemplateStatus.ARCHIVED)));

        verify(templateService, never()).setStatus(any(), any());
    }

    @Test
    void aMissingStatusIsRejectedRatherThanTreatedAsADefault() {
        // Defaulting a null to DRAFT would silently take a live template out of
        // use on a malformed request.
        assertThrows(BadRequestException.class,
                () -> controller.setStatus(principal, templateId,
                        new TemplateController.SetStatusRequest(null)));
        verify(templateService, never()).setStatus(any(), any());
    }

    @Test
    void theRequestedStatusIsWhatGetsWritten() {
        controller.setStatus(principal, templateId,
                new TemplateController.SetStatusRequest(TemplateStatus.ARCHIVED));
        verify(templateService).setStatus(eq(templateId), eq(TemplateStatus.ARCHIVED));
        assertEquals(TemplateStatus.ARCHIVED, TemplateStatus.valueOf("ARCHIVED"));
    }
}
