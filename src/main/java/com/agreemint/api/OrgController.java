package com.agreemint.api;

import com.agreemint.api.dto.OrgMembershipResponse;
import com.agreemint.api.dto.OrgResponse;
import com.agreemint.domain.OrgRole;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.OrgService;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Organizations", description = "Workspace management, members, roles")
@RestController
@RequestMapping("/api/orgs")
public class OrgController {

    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    @GetMapping
    public List<OrgResponse> listMyOrgs(@AuthenticationPrincipal UserPrincipal principal) {
        return orgService.listUserOrgs(principal.userId());
    }

    @PostMapping
    public ResponseEntity<OrgResponse> createOrg(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody CreateOrgRequest req
    ) {
        OrgResponse org = orgService.createOrg(principal.userId(), req.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(org);
    }

    @GetMapping("/{orgId}")
    public OrgResponse getOrg(@PathVariable UUID orgId) {
        return orgService.getOrg(orgId);
    }

    @PutMapping("/{orgId}")
    public OrgResponse updateOrg(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID orgId,
            @RequestBody UpdateOrgRequest req
    ) {
        return orgService.updateOrg(principal.userId(), orgId, req.name(), req.logoUrl());
    }

    @GetMapping("/{orgId}/members")
    public List<OrgMembershipResponse> listMembers(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID orgId
    ) {
        return orgService.listMembers(principal.userId(), orgId);
    }

    @PostMapping("/{orgId}/members")
    public ResponseEntity<OrgMembershipResponse> inviteMember(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID orgId,
            @RequestBody InviteMemberRequest req
    ) {
        OrgRole role = req.role() != null ? OrgRole.valueOf(req.role().toUpperCase()) : OrgRole.VIEWER;
        OrgMembershipResponse result = orgService.inviteMember(principal.userId(), orgId, req.email(), role);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PutMapping("/{orgId}/members/{membershipId}")
    public OrgMembershipResponse changeRole(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID orgId,
            @PathVariable UUID membershipId,
            @RequestBody ChangeRoleRequest req
    ) {
        OrgRole role = OrgRole.valueOf(req.role().toUpperCase());
        return orgService.changeRole(principal.userId(), orgId, membershipId, role);
    }

    @DeleteMapping("/{orgId}/members/{membershipId}")
    public ResponseEntity<Void> removeMember(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID orgId,
            @PathVariable UUID membershipId
    ) {
        orgService.removeMember(principal.userId(), orgId, membershipId);
        return ResponseEntity.noContent().build();
    }

    // ── Request bodies ──

    record CreateOrgRequest(@NotBlank String name) {}
    record UpdateOrgRequest(String name, String logoUrl) {}
    record InviteMemberRequest(@NotBlank @Email String email, String role) {}
    record ChangeRoleRequest(@NotBlank String role) {}
}
