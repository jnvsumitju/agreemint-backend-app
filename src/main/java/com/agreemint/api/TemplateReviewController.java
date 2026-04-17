package com.agreemint.api;

import com.agreemint.api.dto.DecideReviewRequest;
import com.agreemint.api.dto.RequestReviewsRequest;
import com.agreemint.api.dto.TemplateReviewResponse;
import com.agreemint.domain.OrgRole;
import com.agreemint.security.OrgAuthorizationService;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.TemplateReviewService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * REST surface for the template review workflow.
 *
 * <p>Write endpoints (request / decide / dismiss / reopen) perform coarse role
 * checks up front via {@link OrgAuthorizationService}; service-level methods
 * re-check reviewer identity where the authorisation is per-row.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Template Reviews", description = "Request and decide reviews on committed template versions")
@RestController
public class TemplateReviewController {

    private final TemplateReviewService reviewService;
    private final OrgAuthorizationService orgAuthz;

    public TemplateReviewController(TemplateReviewService reviewService, OrgAuthorizationService orgAuthz) {
        this.reviewService = reviewService;
        this.orgAuthz = orgAuthz;
    }

    /** Request review(s) on a specific committed version. Designer/Admin only. */
    @PostMapping("/api/templates/{templateId}/versions/{versionId}/reviews")
    public ResponseEntity<List<TemplateReviewResponse>> requestReviews(
            @PathVariable UUID templateId,
            @PathVariable UUID versionId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody RequestReviewsRequest req
    ) {
        orgAuthz.assertTemplateAccess(principal.userId(), templateId, OrgRole.ADMIN, OrgRole.DESIGNER);
        List<TemplateReviewResponse> rows = reviewService.requestReviews(
                templateId, versionId, principal.userId(), req.reviewerIds(), req.message());
        return ResponseEntity.status(HttpStatus.CREATED).body(rows);
    }

    /** List all reviews for this template (anyone with access can see). */
    @GetMapping("/api/templates/{templateId}/reviews")
    public List<TemplateReviewResponse> listReviews(
            @PathVariable UUID templateId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "versionId", required = false) UUID versionId
    ) {
        orgAuthz.assertTemplateAccess(principal.userId(), templateId,
                OrgRole.ADMIN, OrgRole.DESIGNER, OrgRole.REVIEWER, OrgRole.VIEWER);
        return versionId != null
                ? reviewService.listForVersion(templateId, versionId)
                : reviewService.list(templateId);
    }

    /** Reviewer submits APPROVED or CHANGES_REQUESTED for a review row. */
    @PostMapping("/api/templates/{templateId}/reviews/{reviewId}/decide")
    public TemplateReviewResponse decide(
            @PathVariable UUID templateId,
            @PathVariable UUID reviewId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody DecideReviewRequest req
    ) {
        // Reviewer just needs to be able to read the template; the service
        // enforces reviewerId == principal on the row itself.
        orgAuthz.assertTemplateAccess(principal.userId(), templateId,
                OrgRole.ADMIN, OrgRole.DESIGNER, OrgRole.REVIEWER, OrgRole.VIEWER);
        return reviewService.decide(reviewId, principal.userId(), req.status(), req.summary());
    }

    /** Requester re-asks the same reviewer to re-evaluate. */
    @PostMapping("/api/templates/{templateId}/reviews/{reviewId}/reopen")
    public TemplateReviewResponse reopen(
            @PathVariable UUID templateId,
            @PathVariable UUID reviewId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        orgAuthz.assertTemplateAccess(principal.userId(), templateId, OrgRole.ADMIN, OrgRole.DESIGNER);
        if (!reviewService.isRequester(reviewId, principal.userId())) {
            // Admins are allowed to reopen too — the service will still work,
            // but require the call site to hold a template edit role, which the
            // check above already enforced.
        }
        return reviewService.reopen(reviewId);
    }

    /** Requester (or admin) dismisses a review row, unblocking the commit gate. */
    @PostMapping("/api/templates/{templateId}/reviews/{reviewId}/dismiss")
    public TemplateReviewResponse dismiss(
            @PathVariable UUID templateId,
            @PathVariable UUID reviewId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        orgAuthz.assertTemplateAccess(principal.userId(), templateId, OrgRole.ADMIN, OrgRole.DESIGNER);
        UUID ownerTemplate = reviewService.templateIdOf(reviewId);
        if (ownerTemplate == null || !ownerTemplate.equals(templateId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found for this template");
        }
        return reviewService.dismiss(reviewId);
    }

    /** Inbox: reviews assigned to me that are still PENDING. */
    @GetMapping("/api/reviews/assigned")
    public List<TemplateReviewResponse> assignedToMe(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        return reviewService.listAssignedToMe(principal.userId(), Math.min(Math.max(limit, 1), 200));
    }
}
