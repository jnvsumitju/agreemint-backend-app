package com.agreemint.api;

import com.agreemint.api.dto.MarketplaceListingResponse;
import com.agreemint.api.dto.TemplateResponse;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.Template;
import com.agreemint.security.OrgAuthorizationService;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.MarketplaceService;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.agreemint.billing.PlanGate;
import com.agreemint.domain.OrgPlan;
import java.util.List;
import java.util.UUID;

/**
 * Marketplace: browse, publish and clone templates.
 *
 * <p>Starter and up. Every route is gated, not just the writes — being able to
 * read the catalogue is the feature, and the console hides the whole section on
 * free. Gating only the clone would leave a browsable listing behind a hidden
 * nav link, which is the sort of half-measure that turns into a support ticket
 * the first time someone shares a URL.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Marketplace", description = "Browse, publish, and clone templates")
@RestController
@RequestMapping("/api/marketplace")
public class MarketplaceController {

    /** The tier the marketplace starts at. One constant so the four gates cannot drift. */
    private static final OrgPlan REQUIRED_PLAN = OrgPlan.STARTER;
    private static final String FEATURE = "Template marketplace";

    private final MarketplaceService marketplaceService;
    private final PlanGate planGate;
    private final OrgAuthorizationService orgAuthz;

    public MarketplaceController(MarketplaceService marketplaceService, PlanGate planGate,
                                 OrgAuthorizationService orgAuthz) {
        this.marketplaceService = marketplaceService;
        this.planGate = planGate;
        this.orgAuthz = orgAuthz;
    }

    @GetMapping
    public List<MarketplaceListingResponse> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String category
    ) {
        planGate.requireAtLeast(principal.orgId(), REQUIRED_PLAN, FEATURE);
        if (category != null && !category.isBlank()) {
            return marketplaceService.listByCategory(category);
        }
        return marketplaceService.listPublished();
    }

    @GetMapping("/{id}")
    public MarketplaceListingResponse get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id
    ) {
        planGate.requireAtLeast(principal.orgId(), REQUIRED_PLAN, FEATURE);
        return marketplaceService.getById(id);
    }

    @PostMapping
    public ResponseEntity<MarketplaceListingResponse> publish(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody PublishRequest req
    ) {
        planGate.requireAtLeast(principal.orgId(), REQUIRED_PLAN, FEATURE);

        // sourceTemplateId comes straight from the request body, so it is an
        // arbitrary UUID until proven otherwise. Without this check any Starter
        // user could publish another workspace's template as their own listing
        // and then clone it into their own org — a cross-tenant read of
        // customer template content through two ordinary API calls.
        if (req.sourceTemplateId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "sourceTemplateId is required");
        }
        orgAuthz.assertTemplateAccess(principal.userId(), req.sourceTemplateId(),
                OrgRole.ADMIN, OrgRole.DESIGNER);

        MarketplaceListingResponse response = marketplaceService.publish(
                principal.userId(),
                req.authorName(),
                req.sourceTemplateId(),
                req.title(),
                req.description(),
                req.category(),
                req.tags()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Listings published by the caller's workspace, withdrawn ones included. */
    @GetMapping("/mine")
    public List<MarketplaceListingResponse> mine(@AuthenticationPrincipal UserPrincipal principal) {
        planGate.requireAtLeast(principal.orgId(), REQUIRED_PLAN, FEATURE);
        orgAuthz.assertRole(principal.userId(), principal.orgId(),
                OrgRole.ADMIN, OrgRole.DESIGNER);
        return marketplaceService.listByOrg(principal.orgId());
    }

    /**
     * Withdraw one of your workspace's listings from the catalogue.
     *
     * <p>There is no moderation queue, so publishing is instantly visible to
     * every other Starter+ workspace. This is the only way to take that back,
     * which makes it a safety control rather than a convenience — a template
     * published by mistake can carry real commercial terms.
     *
     * <p>Withdrawal does not affect copies already installed; those are
     * independent templates in someone else's workspace.
     */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<Void> withdraw(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id
    ) {
        planGate.requireAtLeast(principal.orgId(), REQUIRED_PLAN, FEATURE);
        // Authorize against the org that owns the LISTING, not the caller's
        // current org — otherwise any admin could withdraw anyone's listing.
        UUID ownerOrgId = marketplaceService.ownerOrgId(id);
        if (ownerOrgId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This listing has no owning workspace");
        }
        orgAuthz.assertRole(principal.userId(), ownerOrgId, OrgRole.ADMIN, OrgRole.DESIGNER);
        marketplaceService.withdraw(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/clone")
    public ResponseEntity<CloneResponse> clone(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id
    ) {
        planGate.requireAtLeast(principal.orgId(), REQUIRED_PLAN, FEATURE);
        // Installing creates a template in the caller's workspace, so it needs
        // the same role as creating one. The plan gate alone let a VIEWER — who
        // cannot create a template through any other route — add one here.
        orgAuthz.assertRole(principal.userId(), principal.orgId(),
                OrgRole.ADMIN, OrgRole.DESIGNER);
        Template cloned = marketplaceService.cloneTemplate(
                id, principal.orgId(), principal.userId()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CloneResponse(cloned.getId(), cloned.getName()));
    }

    // ── Request / Response bodies ──

    record PublishRequest(
            @NotBlank String title,
            String description,
            String authorName,
            UUID sourceTemplateId,
            String category,
            String tags
    ) {}

    record CloneResponse(UUID templateId, String templateName) {}
}
