package com.agreemint.api;

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
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Templates", description = "Template CRUD, versions, drafts, export/import")
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService templateService;
    private final TemplateVersionService templateVersionService;
    private final TemplateDraftService templateDraftService;
    private final OrgAuthorizationService orgAuthz;

    public TemplateController(
            TemplateService templateService,
            TemplateVersionService templateVersionService,
            TemplateDraftService templateDraftService,
            OrgAuthorizationService orgAuthz) {
        this.templateService = templateService;
        this.templateVersionService = templateVersionService;
        this.templateDraftService = templateDraftService;
        this.orgAuthz = orgAuthz;
    }

    @PostMapping
    public TemplateResponse create(@Valid @RequestBody CreateTemplateRequest request) {
        return templateService.create(request);
    }

    @GetMapping
    public List<TemplateResponse> list() {
        return templateService.listAll();
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

    @GetMapping("/{id}/versions")
    public List<TemplateVersionResponse> listVersions(@PathVariable("id") UUID templateId) {
        return templateVersionService.listVersions(templateId);
    }

    @GetMapping("/{id}/versions/{versionId}")
    public TemplateVersionResponse getVersion(
            @PathVariable("id") UUID templateId,
            @PathVariable UUID versionId) {
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
            @RequestBody com.fasterxml.jackson.databind.JsonNode body
    ) {
        String name = body.has("name") ? body.get("name").asText() : "Imported Template";
        CreateTemplateRequest req = new CreateTemplateRequest(name, null);
        TemplateResponse created = templateService.create(req);

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
