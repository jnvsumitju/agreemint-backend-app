package com.agreemint.api;

import com.agreemint.api.dto.DocumentDetailResponse;
import com.agreemint.api.dto.DocumentLifecycleResponse;
import com.agreemint.api.dto.LifecycleStatsResponse;
import com.agreemint.api.dto.SetDocumentExpiryRequest;
import com.agreemint.api.dto.PendingApprovalResponse;
import com.agreemint.api.dto.TransitionStatusRequest;
import com.agreemint.billing.PlanGate;
import com.agreemint.domain.OrgPlan;
import com.agreemint.domain.DocumentSource;
import com.agreemint.domain.LifecycleStatus;
import com.agreemint.domain.OrgRole;
import com.agreemint.security.OrgAuthorizationService;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.ApprovalWorkflowService;
import com.agreemint.service.DocumentLifecycleService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Document Lifecycle", description = "Document lifecycle management")
@RestController
@RequestMapping("/api/documents")
public class DocumentLifecycleController {

    private final DocumentLifecycleService lifecycleService;
    private final ApprovalWorkflowService approvalService;
    private final OrgAuthorizationService authorizationService;
    private final PlanGate planGate;

    public DocumentLifecycleController(DocumentLifecycleService lifecycleService,
                                        ApprovalWorkflowService approvalService,
                                        OrgAuthorizationService authorizationService,
                                        PlanGate planGate) {
        this.lifecycleService = lifecycleService;
        this.approvalService = approvalService;
        this.authorizationService = authorizationService;
        this.planGate = planGate;
    }

    @GetMapping
    public List<DocumentLifecycleResponse> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) LifecycleStatus status,
            @RequestParam(required = false) DocumentSource source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        authorizationService.assertRole(principal.userId(), principal.orgId(),
                OrgRole.ADMIN, OrgRole.DESIGNER, OrgRole.REVIEWER, OrgRole.VIEWER);
        return lifecycleService.listDocuments(principal.orgId(), source, status, page, size);
    }

    @GetMapping("/{id}/lifecycle")
    public DocumentDetailResponse detail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        authorizationService.assertRole(principal.userId(), principal.orgId(),
                OrgRole.ADMIN, OrgRole.DESIGNER, OrgRole.REVIEWER, OrgRole.VIEWER);
        return lifecycleService.getDocumentWithTimeline(id, principal.orgId());
    }

    @PostMapping("/{id}/transition")
    public DocumentLifecycleResponse transition(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody TransitionStatusRequest request) {
        authorizationService.assertRole(principal.userId(), principal.orgId(),
                OrgRole.ADMIN, OrgRole.DESIGNER);
        // Paid feature. Reads stay open, so a lapsed workspace can still see
        // where its documents got to — it just cannot move them further.
        planGate.requireAtLeast(principal.orgId(), OrgPlan.PRO, "Document lifecycle");
        return lifecycleService.transitionStatus(id, request.targetStatus(),
                principal.userId(), request.comment(), principal.orgId());
    }

    /**
     * Set or clear a document's expiration date.
     *
     * <p>Gated at PRO to match {@code /transition}: an expiry date is only
     * meaningful because a lifecycle transition enforces it, so selling one
     * without the other would let a workspace set a date that nothing acts on.
     */
    @PutMapping("/{id}/expiry")
    public DocumentLifecycleResponse setExpiry(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestBody SetDocumentExpiryRequest request) {
        authorizationService.assertRole(principal.userId(), principal.orgId(),
                OrgRole.ADMIN, OrgRole.DESIGNER);
        planGate.requireAtLeast(principal.orgId(), OrgPlan.PRO, "Document lifecycle");
        return lifecycleService.setExpiry(id, request.expiresAt(), principal.userId(),
                principal.orgId());
    }

    @GetMapping("/stats")
    public LifecycleStatsResponse stats(@AuthenticationPrincipal UserPrincipal principal) {
        authorizationService.assertRole(principal.userId(), principal.orgId(),
                OrgRole.ADMIN, OrgRole.DESIGNER, OrgRole.REVIEWER, OrgRole.VIEWER);
        return lifecycleService.getLifecycleStats(principal.orgId());
    }

    @GetMapping("/pending-approvals")
    public List<PendingApprovalResponse> pendingApprovals(
            @AuthenticationPrincipal UserPrincipal principal) {
        return approvalService.getPendingApprovals(principal.userId());
    }
}
