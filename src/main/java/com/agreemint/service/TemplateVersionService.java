package com.agreemint.service;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.CreateVersionRequest;
import com.agreemint.api.dto.TemplateVersionResponse;
import com.agreemint.domain.Template;
import com.agreemint.domain.TemplateVersion;
import com.agreemint.repository.TemplateRepository;
import com.agreemint.pdf.LayoutBehaviourValidator;
import com.agreemint.repository.TemplateVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TemplateVersionService {

    private final TemplateRepository templateRepository;
    private final TemplateVersionRepository templateVersionRepository;
    private final ObjectMapper objectMapper;

    public TemplateVersionService(
            TemplateRepository templateRepository,
            TemplateVersionRepository templateVersionRepository,
            ObjectMapper objectMapper) {
        this.templateRepository = templateRepository;
        this.templateVersionRepository = templateVersionRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TemplateVersionResponse createVersion(UUID templateId, CreateVersionRequest request) {
        Template template = templateRepository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Template not found"));

        int next = templateVersionRepository.findFirstByTemplateOrderByVersionNumberDesc(template)
                .map(v -> v.getVersionNumber() + 1)
                .orElse(1);

        JsonNode layoutNode = request.layout();
        if (layoutNode == null || layoutNode.isNull()) {
            layoutNode = defaultLayout();
        }
        validateLayout(layoutNode);

        TemplateVersion v = new TemplateVersion();
        v.setTemplate(template);
        v.setVersionNumber(next);
        v.setLayoutJson(layoutNode);
        v.setVariables(request.variables());
        templateVersionRepository.save(v);

        return toResponse(v);
    }

    @Transactional(readOnly = true)
    public List<TemplateVersionResponse> listVersions(UUID templateId) {
        if (!templateRepository.existsById(templateId)) {
            throw new NotFoundException("Template not found");
        }
        return templateVersionRepository.findByTemplate_IdOrderByVersionNumberDesc(templateId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public TemplateVersionResponse getVersion(UUID templateId, UUID versionId) {
        TemplateVersion v = templateVersionRepository.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        if (!v.getTemplate().getId().equals(templateId)) {
            throw new NotFoundException("Version not found");
        }
        return toResponse(v);
    }

    @Transactional(readOnly = true)
    public TemplateVersion getVersionEntity(UUID templateId, UUID versionId) {
        TemplateVersion v = templateVersionRepository.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        if (!v.getTemplate().getId().equals(templateId)) {
            throw new BadRequestException("Version does not belong to template");
        }
        return v;
    }

    private void validateLayout(JsonNode layout) {
        JsonNode pages = layout.path("pages");
        if (pages.isArray() && !pages.isEmpty()) {
            int i = 0;
            for (JsonNode p : pages) {
                if (!p.path("elements").isArray()) {
                    throw new BadRequestException("layout.pages[" + i + "].elements must be an array");
                }
                i++;
            }
            return;
        }
        if (!layout.has("elements") || !layout.get("elements").isArray()) {
            throw new BadRequestException("layout must contain an elements array or a non-empty pages array");
        }
    }

    /** Used by draft preview and PDF preview endpoints. */
    public void assertValidLayout(JsonNode layout) {
        validateLayout(layout);
        LayoutBehaviourValidator.validateLayoutElements(layout);
    }

    private JsonNode defaultLayout() {
        ObjectNode page = objectMapper.createObjectNode();
        page.put("size", "A4");
        page.put("margin", 40);
        ObjectNode root = objectMapper.createObjectNode();
        root.set("page", page);
        root.set("elements", objectMapper.createArrayNode());
        return root;
    }

    private TemplateVersionResponse toResponse(TemplateVersion v) {
        return new TemplateVersionResponse(
                v.getId(),
                v.getTemplate().getId(),
                v.getVersionNumber(),
                v.getLayoutJson(),
                v.getVariables(),
                v.getCreatedAt()
        );
    }
}
