package com.agreemint.service;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.CreateVersionRequest;
import com.agreemint.api.dto.TemplateDraftResponse;
import com.agreemint.api.dto.TemplateVersionResponse;
import com.agreemint.api.dto.UpsertDraftRequest;
import com.agreemint.domain.TemplateDraft;
import com.agreemint.repository.TemplateDraftRepository;
import com.agreemint.repository.TemplateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class TemplateDraftService {

    private final TemplateRepository templateRepository;
    private final TemplateDraftRepository templateDraftRepository;
    private final TemplateVersionService templateVersionService;

    public TemplateDraftService(
            TemplateRepository templateRepository,
            TemplateDraftRepository templateDraftRepository,
            TemplateVersionService templateVersionService) {
        this.templateRepository = templateRepository;
        this.templateDraftRepository = templateDraftRepository;
        this.templateVersionService = templateVersionService;
    }

    @Transactional(readOnly = true)
    public Optional<TemplateDraftResponse> getDraft(UUID templateId) {
        if (!templateRepository.existsById(templateId)) {
            throw new NotFoundException("Template not found");
        }
        return templateDraftRepository.findById(templateId).map(this::toResponse);
    }

    @Transactional
    public TemplateDraftResponse upsertDraft(UUID templateId, UpsertDraftRequest request) {
        if (!templateRepository.existsById(templateId)) {
            throw new NotFoundException("Template not found");
        }
        JsonNode layout = request.layout();
        if (layout == null || layout.isNull()) {
            throw new BadRequestException("layout is required");
        }
        JsonNode variables = request.variables();
        if (variables == null || variables.isNull()) {
            variables = JsonNodeFactory.instance.objectNode();
        }

        TemplateDraft d = templateDraftRepository.findById(templateId).orElseGet(() -> {
            TemplateDraft n = new TemplateDraft();
            n.setTemplateId(templateId);
            return n;
        });
        d.setLayoutJson(layout);
        d.setVariables(variables);
        d.setUpdatedAt(Instant.now());
        templateDraftRepository.save(d);
        return toResponse(d);
    }

    @Transactional
    public TemplateVersionResponse commitDraft(UUID templateId) {
        TemplateDraft d = templateDraftRepository.findById(templateId)
                .orElseThrow(() -> new BadRequestException(
                        "No draft to commit. Wait for autosave or edit the template first."));
        JsonNode vars = d.getVariables();
        if (vars == null || vars.isNull()) {
            vars = JsonNodeFactory.instance.objectNode();
        }
        CreateVersionRequest req = new CreateVersionRequest(d.getLayoutJson(), vars);
        TemplateVersionResponse created = templateVersionService.createVersion(templateId, req);
        templateDraftRepository.deleteById(templateId);
        return created;
    }

    private TemplateDraftResponse toResponse(TemplateDraft d) {
        return new TemplateDraftResponse(d.getLayoutJson(), d.getVariables(), d.getUpdatedAt());
    }
}
