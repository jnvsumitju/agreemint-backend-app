package com.agreemint.api;

import com.agreemint.api.dto.ProductRequest;
import com.agreemint.api.dto.ProductResponse;
import com.agreemint.domain.OrgRole;
import com.agreemint.security.OrgAuthorizationService;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.ProductService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Org-scoped product CRUD. Any member can list (so a designer sees what's
 * available when creating a template); only ADMINs can create or rename.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Products",
        description = "Org-scoped product catalog that templates group under")
@RestController
@RequestMapping("/api/orgs/{orgId}/products")
public class ProductController {

    private final ProductService productService;
    private final OrgAuthorizationService orgAuthz;

    public ProductController(ProductService productService, OrgAuthorizationService orgAuthz) {
        this.productService = productService;
        this.orgAuthz = orgAuthz;
    }

    @GetMapping
    public List<ProductResponse> list(
            @PathVariable UUID orgId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        orgAuthz.assertRole(principal.userId(), orgId,
                OrgRole.ADMIN, OrgRole.DESIGNER, OrgRole.REVIEWER, OrgRole.VIEWER);
        return productService.list(orgId).stream().map(ProductResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(
            @PathVariable UUID orgId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody ProductRequest body
    ) {
        orgAuthz.assertRole(principal.userId(), orgId, OrgRole.ADMIN);
        var created = productService.create(orgId, principal.userId(),
                body == null ? null : body.name(),
                body == null ? null : body.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.from(created));
    }

    @PutMapping("/{productId}")
    public ProductResponse update(
            @PathVariable UUID orgId,
            @PathVariable UUID productId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody ProductRequest body
    ) {
        orgAuthz.assertRole(principal.userId(), orgId, OrgRole.ADMIN);
        return ProductResponse.from(productService.rename(productId, orgId,
                body == null ? null : body.name(),
                body == null ? null : body.description()));
    }
}
