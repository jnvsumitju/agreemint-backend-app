package com.agreemint.api;

import com.agreemint.api.dto.DocumentDetailResponse;
import com.agreemint.api.dto.DocumentLifecycleResponse;
import com.agreemint.api.dto.LifecycleStatsResponse;
import com.agreemint.api.dto.PendingApprovalResponse;
import com.agreemint.api.dto.TransitionStatusRequest;
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

    public DocumentLifecycleController(DocumentLifecycleService lifecycleService,
                                        ApprovalWorkflowService approvalService,
                                        OrgAuthorizationService authorizationService) {
        this.lifecycleService = lifecycleService;
        this.approvalService = approvalService;
        this.authorizationService = authorizationService;
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
        return lifecycleService.getDocumentWithTimeline(id);
    }

    @PostMapping("/{id}/transition")
    public DocumentLifecycleResponse transition(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody TransitionStatusRequest request) {
        authorizationService.assertRole(principal.userId(), principal.orgId(),
                OrgRole.ADMIN, OrgRole.DESIGNER);
        return lifecycleService.transitionStatus(id, request.targetStatus(),
                principal.userId(), request.comment());
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
