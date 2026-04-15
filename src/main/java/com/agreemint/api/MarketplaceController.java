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

import java.util.List;
import java.util.UUID;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Marketplace", description = "Browse, publish, and clone templates")
@RestController
@RequestMapping("/api/marketplace")
public class MarketplaceController {

    private final MarketplaceService marketplaceService;

    public MarketplaceController(MarketplaceService marketplaceService) {
        this.marketplaceService = marketplaceService;
    }

    @GetMapping
    public List<MarketplaceListingResponse> list(
            @RequestParam(required = false) String category
    ) {
        if (category != null && !category.isBlank()) {
            return marketplaceService.listByCategory(category);
        }
        return marketplaceService.listPublished();
    }

    @GetMapping("/{id}")
    public MarketplaceListingResponse get(@PathVariable UUID id) {
        return marketplaceService.getById(id);
    }

    @PostMapping
    public ResponseEntity<MarketplaceListingResponse> publish(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody PublishRequest req
    ) {
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
