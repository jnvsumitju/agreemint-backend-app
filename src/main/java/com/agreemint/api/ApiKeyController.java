package com.agreemint.api;

import com.agreemint.billing.PlanGate;
import com.agreemint.domain.OrgPlan;
import com.agreemint.api.dto.ApiKeyCreatedResponse;
import com.agreemint.api.dto.ApiKeyResponse;
import com.agreemint.api.dto.CreateApiKeyRequest;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.User;
import com.agreemint.repository.UserRepository;
import com.agreemint.security.OrgAuthorizationService;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.ApiKeyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * ADMIN-only management endpoints for org API keys. Management is always JWT
 * authenticated (no API key can manage keys — avoids the bootstrap recursion).
 *
 * <p>See {@code PublicApiController} for the customer-facing {@code /api/v1/*}
 * surface that these keys authenticate against.
 */
@io.swagger.v3.oas.annotations.tags.Tag(
        name = "API Keys",
        description = "Create and manage API keys for the Developer Platform")
@RestController
@RequestMapping("/api/orgs/{orgId}/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final OrgAuthorizationService orgAuthz;
    private final UserRepository userRepo;
    private final PlanGate planGate;

    public ApiKeyController(
            ApiKeyService apiKeyService,
            OrgAuthorizationService orgAuthz,
            UserRepository userRepo,
            PlanGate planGate) {
        this.apiKeyService = apiKeyService;
        this.orgAuthz = orgAuthz;
        this.userRepo = userRepo;
        this.planGate = planGate;
    }

    @PostMapping
    public ResponseEntity<ApiKeyCreatedResponse> create(
            @PathVariable UUID orgId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody CreateApiKeyRequest req
    ) {
        orgAuthz.assertRole(principal.userId(), orgId, OrgRole.ADMIN);
        // API access starts at Starter. Existing keys keep working — only
        // minting new ones is gated, so a downgrade does not break live
        // integrations without warning.
        planGate.requireAtLeast(orgId, OrgPlan.STARTER, "API access");
        String actorName = userRepo.findById(principal.userId())
                .map(User::getName).orElse(principal.email());
        ApiKeyCreatedResponse created = apiKeyService.create(orgId, principal.userId(), actorName, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<ApiKeyResponse> list(
            @PathVariable UUID orgId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        orgAuthz.assertRole(principal.userId(), orgId, OrgRole.ADMIN);
        return apiKeyService.list(orgId);
    }

    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> revoke(
            @PathVariable UUID orgId,
            @PathVariable UUID keyId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        orgAuthz.assertRole(principal.userId(), orgId, OrgRole.ADMIN);
        String actorName = userRepo.findById(principal.userId())
                .map(User::getName).orElse(principal.email());
        apiKeyService.revoke(orgId, principal.userId(), actorName, keyId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{keyId}/rotate")
    public ResponseEntity<ApiKeyCreatedResponse> rotate(
            @PathVariable UUID orgId,
            @PathVariable UUID keyId,
            @RequestParam(value = "graceDays", defaultValue = "7") int graceDays,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        orgAuthz.assertRole(principal.userId(), orgId, OrgRole.ADMIN);
        String actorName = userRepo.findById(principal.userId())
                .map(User::getName).orElse(principal.email());
        ApiKeyCreatedResponse created = apiKeyService.rotate(
                orgId, principal.userId(), actorName, keyId, graceDays);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
