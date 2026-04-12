package com.agreemint.api;

import com.agreemint.api.dto.CreateTemplateRequest;
import com.agreemint.api.dto.CreateVersionRequest;
import com.agreemint.api.dto.TemplateDraftResponse;
import com.agreemint.api.dto.TemplateResponse;
import com.agreemint.api.dto.TemplateVersionResponse;
import com.agreemint.api.dto.UpsertDraftRequest;
import com.agreemint.service.TemplateDraftService;
import com.agreemint.service.TemplateService;
import com.agreemint.service.TemplateVersionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService templateService;
    private final TemplateVersionService templateVersionService;
    private final TemplateDraftService templateDraftService;

    public TemplateController(
            TemplateService templateService,
            TemplateVersionService templateVersionService,
            TemplateDraftService templateDraftService) {
        this.templateService = templateService;
        this.templateVersionService = templateVersionService;
        this.templateDraftService = templateDraftService;
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
}
