package com.agreemint.api;

import com.agreemint.billing.PlanGate;
import com.agreemint.api.dto.ApprovalDecisionRequest;
import com.agreemint.api.dto.ApprovalWorkflowResponse;
import com.agreemint.api.dto.CreateApprovalWorkflowRequest;
import com.agreemint.domain.OrgRole;
import com.agreemint.security.OrgAuthorizationService;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.ApprovalWorkflowService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Approvals", description = "Multi-step approval workflow management")
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalWorkflowService approvalService;
    private final OrgAuthorizationService authorizationService;
    private final PlanGate planGate;

    public ApprovalController(ApprovalWorkflowService approvalService,
                               OrgAuthorizationService authorizationService,
                               PlanGate planGate) {
        this.approvalService = approvalService;
        this.authorizationService = authorizationService;
        this.planGate = planGate;
    }

    @PostMapping("/workflows")
    public ApprovalWorkflowResponse createWorkflow(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateApprovalWorkflowRequest request) {
        authorizationService.assertRole(principal.userId(), principal.orgId(),
                OrgRole.ADMIN, OrgRole.DESIGNER);
        // Creating a workflow is the paid action. approve/reject stay open so an
        // in-flight approval is not stranded if a subscription lapses mid-review.
        planGate.requirePaid(principal.orgId(), "Approval workflows");
        return approvalService.createWorkflow(request.documentId(), principal.orgId(),
                principal.userId(), request.steps());
    }

    @GetMapping("/workflows/{documentId}")
    public ApprovalWorkflowResponse getWorkflow(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID documentId) {
        authorizationService.assertRole(principal.userId(), principal.orgId(),
                OrgRole.ADMIN, OrgRole.DESIGNER, OrgRole.REVIEWER, OrgRole.VIEWER);
        return approvalService.getWorkflow(documentId);
    }

    @PostMapping("/steps/{stepId}/approve")
    public ApprovalWorkflowResponse approveStep(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID stepId,
            @RequestBody(required = false) ApprovalDecisionRequest request) {
        return approvalService.approveStep(stepId, principal.userId(),
                request != null ? request.comment() : null);
    }

    @PostMapping("/steps/{stepId}/reject")
    public ApprovalWorkflowResponse rejectStep(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID stepId,
            @RequestBody(required = false) ApprovalDecisionRequest request) {
        return approvalService.rejectStep(stepId, principal.userId(),
                request != null ? request.comment() : null);
    }
}
