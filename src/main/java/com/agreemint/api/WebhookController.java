package com.agreemint.api;

import com.agreemint.api.dto.CreateWebhookRequest;
import com.agreemint.api.dto.WebhookCreatedResponse;
import com.agreemint.api.dto.WebhookDeliveryResponse;
import com.agreemint.api.dto.WebhookResponse;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.User;
import com.agreemint.repository.UserRepository;
import com.agreemint.security.OrgAuthorizationService;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.WebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * ADMIN-only CRUD for webhooks. All operations are JWT-auth; the raw signing
 * secret is returned once at creation via {@link WebhookCreatedResponse}.
 */
@io.swagger.v3.oas.annotations.tags.Tag(
        name = "Webhooks",
        description = "Outbound HMAC-signed webhooks for the Developer Platform")
@RestController
@RequestMapping("/api/orgs/{orgId}/webhooks")
public class WebhookController {

    private final WebhookService webhookService;
    private final OrgAuthorizationService orgAuthz;
    private final UserRepository userRepo;

    public WebhookController(WebhookService webhookService,
                              OrgAuthorizationService orgAuthz,
                              UserRepository userRepo) {
        this.webhookService = webhookService;
        this.orgAuthz = orgAuthz;
        this.userRepo = userRepo;
    }

    @PostMapping
    public ResponseEntity<WebhookCreatedResponse> create(
            @PathVariable UUID orgId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody CreateWebhookRequest req
    ) {
        orgAuthz.assertRole(principal.userId(), orgId, OrgRole.ADMIN);
        String actorName = userRepo.findById(principal.userId()).map(User::getName).orElse(principal.email());
        WebhookCreatedResponse res = webhookService.create(orgId, principal.userId(), actorName, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    @GetMapping
    public List<WebhookResponse> list(
            @PathVariable UUID orgId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        orgAuthz.assertRole(principal.userId(), orgId, OrgRole.ADMIN);
        return webhookService.list(orgId);
    }

    @DeleteMapping("/{webhookId}")
    public ResponseEntity<Void> revoke(
            @PathVariable UUID orgId,
            @PathVariable UUID webhookId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        orgAuthz.assertRole(principal.userId(), orgId, OrgRole.ADMIN);
        String actorName = userRepo.findById(principal.userId()).map(User::getName).orElse(principal.email());
        webhookService.revoke(orgId, principal.userId(), actorName, webhookId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{webhookId}/deliveries")
    public List<WebhookDeliveryResponse> deliveries(
            @PathVariable UUID orgId,
            @PathVariable UUID webhookId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        orgAuthz.assertRole(principal.userId(), orgId, OrgRole.ADMIN);
        return webhookService.listDeliveries(orgId, webhookId, limit);
    }
}
