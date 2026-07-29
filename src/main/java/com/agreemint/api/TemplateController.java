package com.agreemint.api;

import com.agreemint.billing.PlanGate;
import com.agreemint.api.dto.CreateTemplateRequest;
import com.agreemint.api.dto.CreateVersionRequest;
import com.agreemint.api.dto.TemplateDraftResponse;
import com.agreemint.api.dto.TemplateAccessResponse;
import com.agreemint.api.dto.TemplateResponse;
import com.agreemint.api.dto.TemplateVersionResponse;
import com.agreemint.api.dto.UpsertDraftRequest;
import com.agreemint.domain.OrgRole;
import com.agreemint.security.OrgAuthorizationService;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.TemplateDraftService;
import com.agreemint.service.TemplateService;
import com.agreemint.service.TemplateVersionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Templates", description = "Template CRUD, versions, drafts, export/import")
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private static final Logger log = LoggerFactory.getLogger(TemplateController.class);

    private final TemplateService templateService;
    private final TemplateVersionService templateVersionService;
    private final TemplateDraftService templateDraftService;
    private final OrgAuthorizationService orgAuthz;
    private final PlanGate planGate;

    public TemplateController(
            TemplateService templateService,
            TemplateVersionService templateVersionService,
            TemplateDraftService templateDraftService,
            OrgAuthorizationService orgAuthz,
            PlanGate planGate) {
        this.templateService = templateService;
        this.templateVersionService = templateVersionService;
        this.templateDraftService = templateDraftService;
        this.orgAuthz = orgAuthz;
        this.planGate = planGate;
    }

    @PostMapping
    public TemplateResponse create(
            @Valid @RequestBody CreateTemplateRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        // Stamp the authenticated user's active org + id onto the new template.
        // Without these the authorization layer cannot gate access, so ANY
        // subsequent `/access` call would have returned ADMIN for every user.
        if (principal == null || principal.orgId() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "No organization context — cannot create template");
        }
        // Only ADMIN/DESIGNER may create templates in an org.
        orgAuthz.assertRole(principal.userId(), principal.orgId(),
                OrgRole.ADMIN, OrgRole.DESIGNER);
        return templateService.create(request, principal.orgId(), principal.userId());
    }

    @GetMapping
    public List<TemplateResponse> list(
            @RequestParam(value = "productId", required = false) UUID productId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        // Scope to the authenticated user's active org. Without this, the old
        // findAll() fell through and every customer saw every other customer's
        // templates — the tenancy leak reported when two workspaces rendered
        // the same list.
        if (principal == null || principal.orgId() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "No organization context");
        }
        return templateService.listForOrg(principal.orgId(), productId);
    }

    @GetMapping("/{id}")
    public TemplateResponse get(@PathVariable UUID id) {
        return templateService.getResponse(id);
    }

    @PostMapping("/{id}/versions")
    public TemplateVersionResponse createVersion(
            @PathVariable("id") UUID templateId,
            @RequestBody CreateVersionRequest request) {
        return templateVersionService.createVersion(templateId, request);
    }

    /**
     * Hard-delete a template and every row that references it (versions,
     * drafts, reviews, shares — see the {@code ON DELETE CASCADE} in the
     * schema migrations). Marketplace listings sourced from the template
     * survive with a null {@code source_template_id}.
     *
     * <p>Gated to {@code ADMIN} and {@code DESIGNER}. Viewers and reviewers
     * cannot remove templates from an org.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("id") UUID templateId,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "Authentication required");
        }
        log.info("DELETE /api/templates/{} user={}", templateId, principal.userId());
        orgAuthz.assertTemplateAccess(principal.userId(), templateId,
                OrgRole.ADMIN, OrgRole.DESIGNER);
        templateService.delete(templateId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/versions")
    public List<TemplateVersionResponse> listVersions(@PathVariable("id") UUID templateId) {
        return templateVersionService.listVersions(templateId);
    }

    /**
     * Open a specific historical version — this is what powers the version
     * diff and restore, so it is the paid half of "version history".
     *
     * <p>The sibling list endpoint is deliberately NOT gated: the editor
     * bootstraps from it on every load, and gating it would lock free
     * workspaces out of the editor entirely.
     */
    @GetMapping("/{id}/versions/{versionId}")
    public TemplateVersionResponse getVersion(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("id") UUID templateId,
            @PathVariable UUID versionId) {
        planGate.requirePaid(principal.orgId(), "Version history");
        return templateVersionService.getVersion(templateId, versionId);
    }

    @GetMapping("/{id}/draft")
    public ResponseEntity<TemplateDraftResponse> getDraft(@PathVariable("id") UUID templateId) {
        return templateDraftService.getDraft(templateId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/draft")
    public TemplateDraftResponse putDraft(
            @PathVariable("id") UUID templateId,
            @RequestBody UpsertDraftRequest body) {
        return templateDraftService.upsertDraft(templateId, body);
    }

    /**
     * Persists only the draft {@code variables}, leaving {@code layoutJson}
     * untouched. Used for body-cell / variable-value edits that originate
     * outside the collab-op stream (which carries layout ops + variable
     * definitions, but not variable VALUES). Prevents a full PUT /draft from
     * racing the {@code CollabFlushJob}'s latest layout write.
     */
    @PutMapping("/{id}/draft/variables")
    public ResponseEntity<Void> putDraftVariables(
            @PathVariable("id") UUID templateId,
            @RequestBody com.fasterxml.jackson.databind.JsonNode variables) {
        templateDraftService.upsertDraftVariables(templateId, variables);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/draft/commit")
    public TemplateVersionResponse commitDraft(@PathVariable("id") UUID templateId) {
        return templateDraftService.commitDraft(templateId);
    }

    /** Returns the authenticated user's effective role + permissions for this template. */
    @GetMapping("/{id}/access")
    public TemplateAccessResponse getAccess(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        if (principal == null) {
            // Unauthenticated — full access for backward compat during migration
            return new TemplateAccessResponse("ADMIN", true, true);
        }
        OrgRole role = orgAuthz.assertTemplateAccess(
                principal.userId(), id,
                OrgRole.ADMIN, OrgRole.DESIGNER, OrgRole.REVIEWER, OrgRole.VIEWER
        );
        boolean canEdit = role == OrgRole.ADMIN || role == OrgRole.DESIGNER;
        boolean canComment = canEdit || role == OrgRole.REVIEWER;
        return new TemplateAccessResponse(role.name(), canEdit, canComment);
    }

    /** Export template with its latest version layout as a JSON bundle. */
    @GetMapping("/{id}/export")
    public ResponseEntity<java.util.Map<String, Object>> exportTemplate(@PathVariable UUID id) {
        TemplateResponse template = templateService.getResponse(id);
        var versions = templateVersionService.listVersions(id);
        TemplateVersionResponse latest = versions.isEmpty() ? null :
                versions.stream().max(java.util.Comparator.comparing(TemplateVersionResponse::versionNumber)).orElse(null);

        java.util.Map<String, Object> export = new java.util.LinkedHashMap<>();
        export.put("name", template.name());
        export.put("exportedAt", java.time.Instant.now().toString());
        if (latest != null) {
            export.put("layout", latest.layout());
            export.put("variables", latest.variables());
            export.put("versionNumber", latest.versionNumber());
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + template.name().replaceAll("[^a-zA-Z0-9._-]", "_") + ".json\"")
                .body(export);
    }

    /** Import a previously exported template JSON bundle, creating a new template + draft + committed version. */
    @PostMapping("/import")
    public ResponseEntity<TemplateResponse> importTemplate(
            @RequestBody com.fasterxml.jackson.databind.JsonNode body,
            @RequestParam("productId") UUID productId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        if (principal == null || principal.orgId() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "No organization context — cannot import template");
        }
        orgAuthz.assertRole(principal.userId(), principal.orgId(),
                OrgRole.ADMIN, OrgRole.DESIGNER);

        String name = body.has("name") ? body.get("name").asText() : "Imported Template";
        // Imports now require a target product. The frontend import dialog is
        // expected to present the Product dropdown before posting.
        CreateTemplateRequest req = new CreateTemplateRequest(name, null, productId);
        TemplateResponse created = templateService.create(req, principal.orgId(), principal.userId());

        // If the export contains layout, save as draft then commit to create a version
        if (body.has("layout")) {
            com.fasterxml.jackson.databind.JsonNode layout = body.get("layout");
            com.fasterxml.jackson.databind.JsonNode variables = body.has("variables") ? body.get("variables") : null;
            templateDraftService.upsertDraft(created.id(), new UpsertDraftRequest(layout, variables));
            templateDraftService.commitDraft(created.id());
        }

        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(created);
    }
}
