package com.agreemint.api;

import com.agreemint.api.dto.MarketplaceListingResponse;
import com.agreemint.api.dto.TemplateResponse;
import com.agreemint.domain.Template;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.MarketplaceService;
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

    public MarketplaceController(MarketplaceService marketplaceService, PlanGate planGate) {
        this.marketplaceService = marketplaceService;
        this.planGate = planGate;
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
        MarketplaceListingResponse response = marketplaceService.publish(
                principal.userId(),
                req.authorName(),
                principal.orgId(),
                req.sourceTemplateId(),
                req.title(),
                req.description(),
                req.category(),
                req.tags()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/clone")
    public ResponseEntity<CloneResponse> clone(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id
    ) {
        planGate.requireAtLeast(principal.orgId(), REQUIRED_PLAN, FEATURE);
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
