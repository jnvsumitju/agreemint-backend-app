package com.agreemint.service;

import com.agreemint.api.dto.GenerateRequest;
import com.agreemint.api.dto.GenerateResponse;
import com.agreemint.api.dto.GeneratedDocumentResponse;
import com.agreemint.config.StorageProperties;
import com.agreemint.domain.DocumentStatus;
import com.agreemint.domain.GeneratedDocument;
import com.agreemint.domain.TemplateVersion;
import com.agreemint.pdf.PdfRendererService;
import com.agreemint.repository.GeneratedDocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class DocumentGenerationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentGenerationService.class);

    private final TemplateVersionService templateVersionService;
    private final GeneratedDocumentRepository generatedDocumentRepository;
    private final PdfRendererService pdfRendererService;
    private final StorageProperties storageProperties;
    private final WebhookService webhookService;

    public DocumentGenerationService(
            TemplateVersionService templateVersionService,
            GeneratedDocumentRepository generatedDocumentRepository,
            PdfRendererService pdfRendererService,
            StorageProperties storageProperties,
            WebhookService webhookService) {
        this.templateVersionService = templateVersionService;
        this.generatedDocumentRepository = generatedDocumentRepository;
        this.pdfRendererService = pdfRendererService;
        this.storageProperties = storageProperties;
        this.webhookService = webhookService;
    }

    /** Renders PDF from arbitrary layout JSON (e.g. editor preview / local state). */
    @Transactional(readOnly = true)
    public byte[] renderPreviewPdf(JsonNode layout, JsonNode data) {
        if (layout == null || layout.isNull()) {
            throw new com.agreemint.api.BadRequestException("layout is required");
        }
        templateVersionService.assertValidLayout(layout);
        if (data == null || data.isNull()) {
            data = JsonNodeFactory.instance.objectNode();
        }
        try {
            return pdfRendererService.render(layout, data);
        } catch (IOException e) {
            log.error("Preview PDF generation failed (I/O)", e);
            throw new com.agreemint.api.BadRequestException(
                    "PDF generation failed: " + e.getMessage());
        }
    }

    @Transactional
    public GenerateResponse generate(GenerateRequest request) {
        return generate(request, null, null);
    }

    @Transactional
    public GenerateResponse generate(GenerateRequest request, UUID userId, UUID orgId) {
        TemplateVersion version = templateVersionService.getVersionEntity(
                request.templateId(), request.versionId());
        JsonNode data = request.data();
        if (data == null || data.isNull()) {
            data = JsonNodeFactory.instance.objectNode();
        }

        GeneratedDocument doc = new GeneratedDocument();
        doc.setTemplate(version.getTemplate());
        doc.setVersion(version);
        doc.setInputData(data);
        doc.setStatus(DocumentStatus.PENDING);
        doc.setLifecycleStatus(com.agreemint.domain.LifecycleStatus.DRAFT);
        if (userId != null) doc.setCreatedBy(userId);
        if (orgId != null) {
            doc.setOrgId(orgId);
        } else if (version.getTemplate().getOrgId() != null) {
            doc.setOrgId(version.getTemplate().getOrgId());
        }
        generatedDocumentRepository.save(doc);
        generatedDocumentRepository.flush();

        Path root = storageProperties.getRoot();
        Path target = root.resolve(doc.getId() + ".pdf");

        try {
            Files.createDirectories(root);
            byte[] pdf = pdfRendererService.render(version.getLayoutJson(), data);
            Files.write(target, pdf);
            doc.setFileUrl("/api/documents/" + doc.getId() + "/file");
            doc.setStatus(DocumentStatus.COMPLETED);
        } catch (IOException e) {
            log.error("Document PDF generation failed for doc {}", doc.getId(), e);
            doc.setStatus(DocumentStatus.FAILED);
            doc.setFileUrl(null);
            generatedDocumentRepository.save(doc);
            throw new com.agreemint.api.BadRequestException(
                    "PDF generation failed: " + e.getMessage());
        }
        generatedDocumentRepository.save(doc);

        // Webhook emit — fire-and-forget (persisted as PENDING; dispatcher picks up).
        UUID payloadOrgId = doc.getOrgId();
        if (payloadOrgId != null) {
            webhookService.emit(payloadOrgId, "document.generated", java.util.Map.of(
                    "documentId", doc.getId().toString(),
                    "templateId", doc.getTemplate().getId().toString(),
                    "versionId", doc.getVersion().getId().toString(),
                    "status", doc.getStatus().name(),
                    "fileUrl", doc.getFileUrl() == null ? "" : doc.getFileUrl(),
                    "createdAt", doc.getCreatedAt() == null ? "" : doc.getCreatedAt().toString()
            ));
        }

        return new GenerateResponse(doc.getId(), doc.getFileUrl());
    }

    @Transactional(readOnly = true)
    public GeneratedDocumentResponse getDocument(UUID id) {
        GeneratedDocument d = generatedDocumentRepository.findById(id)
                .orElseThrow(() -> new com.agreemint.api.NotFoundException("Document not found"));
        return new GeneratedDocumentResponse(
                d.getId(),
                d.getTemplate().getId(),
                d.getVersion().getId(),
                d.getFileUrl(),
                d.getStatus(),
                d.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public Path resolveFile(UUID documentId) {
        GeneratedDocument d = generatedDocumentRepository.findById(documentId)
                .orElseThrow(() -> new com.agreemint.api.NotFoundException("Document not found"));
        if (d.getStatus() != DocumentStatus.COMPLETED || d.getFileUrl() == null) {
            throw new com.agreemint.api.NotFoundException("PDF not available");
        }
        Path p = storageProperties.getRoot().resolve(d.getId() + ".pdf");
        if (!Files.isRegularFile(p)) {
            throw new com.agreemint.api.NotFoundException("PDF file missing");
        }
        return p;
    }
}
