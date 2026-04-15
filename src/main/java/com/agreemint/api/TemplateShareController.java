package com.agreemint.api;

import com.agreemint.api.dto.TemplateShareResponse;
import com.agreemint.domain.OrgRole;
import com.agreemint.domain.TemplateShare;
import com.agreemint.security.OrgAuthorizationService;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.TemplateShareService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/templates/{templateId}/shares")
public class TemplateShareController {

    private final TemplateShareService shareService;
    private final OrgAuthorizationService orgAuthz;

    public TemplateShareController(TemplateShareService shareService, OrgAuthorizationService orgAuthz) {
        this.shareService = shareService;
        this.orgAuthz = orgAuthz;
    }

    @GetMapping
    public List<TemplateShareResponse> listShares(
            @PathVariable UUID templateId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        orgAuthz.assertTemplateAccess(principal.userId(), templateId, OrgRole.ADMIN, OrgRole.DESIGNER);
        return shareService.listShares(templateId);
    }

    @PostMapping("/user")
    public ResponseEntity<TemplateShareResponse> shareWithUser(
            @PathVariable UUID templateId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody ShareWithUserRequest req
    ) {
        orgAuthz.assertTemplateAccess(principal.userId(), templateId, OrgRole.ADMIN, OrgRole.DESIGNER);
        OrgRole role = req.role() != null ? OrgRole.valueOf(req.role().toUpperCase()) : OrgRole.VIEWER;
        TemplateShareResponse result = shareService.shareWithUser(templateId, req.email(), role, principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/link")
    public ResponseEntity<TemplateShareResponse> generateShareLink(
            @PathVariable UUID templateId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody GenerateShareLinkRequest req
    ) {
        orgAuthz.assertTemplateAccess(principal.userId(), templateId, OrgRole.ADMIN, OrgRole.DESIGNER);
        OrgRole role = req.role() != null ? OrgRole.valueOf(req.role().toUpperCase()) : OrgRole.VIEWER;
        TemplateShareResponse result = shareService.generateShareLink(templateId, role, principal.userId(), req.expiresInHours());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @DeleteMapping("/{shareId}")
    public ResponseEntity<Void> revokeShare(
            @PathVariable UUID templateId,
            @PathVariable UUID shareId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        orgAuthz.assertTemplateAccess(principal.userId(), templateId, OrgRole.ADMIN, OrgRole.DESIGNER);
        shareService.revokeShare(templateId, shareId);
        return ResponseEntity.noContent().build();
    }

    /** Public endpoint — resolve a share token (no auth required). */
    @GetMapping("/resolve")
    public TemplateShareResponse resolveToken(@RequestParam String token) {
        TemplateShare share = shareService.resolveShareToken(token);
        return TemplateShareResponse.from(share);
    }

    // ── Request records ──

    record ShareWithUserRequest(@NotBlank @Email String email, String role) {}
    record GenerateShareLinkRequest(String role, Integer expiresInHours) {}
}
