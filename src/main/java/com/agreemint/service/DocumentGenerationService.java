package com.agreemint.service;

import com.agreemint.api.dto.GenerateRequest;
import com.agreemint.api.dto.GenerateResponse;
import com.agreemint.api.dto.GeneratedDocumentResponse;
import com.agreemint.domain.DocumentSource;
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

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.net.URL;
import java.util.UUID;

@Service
public class DocumentGenerationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentGenerationService.class);

    private final TemplateVersionService templateVersionService;
    private final GeneratedDocumentRepository generatedDocumentRepository;
    private final PdfRendererService pdfRendererService;
    private final R2StorageService r2;
    private final WebhookService webhookService;
    private final com.agreemint.billing.PlanGate planGate;
    private final com.agreemint.billing.PdfQuotaService pdfQuota;

    public DocumentGenerationService(
            TemplateVersionService templateVersionService,
            GeneratedDocumentRepository generatedDocumentRepository,
            PdfRendererService pdfRendererService,
            R2StorageService r2,
            WebhookService webhookService,
            com.agreemint.billing.PlanGate planGate,
            com.agreemint.billing.PdfQuotaService pdfQuota) {
        this.templateVersionService = templateVersionService;
        this.generatedDocumentRepository = generatedDocumentRepository;
        this.pdfRendererService = pdfRendererService;
        this.r2 = r2;
        this.webhookService = webhookService;
        this.planGate = planGate;
        this.pdfQuota = pdfQuota;
    }

    /** R2 object key under the private documents bucket for a document id. */
    public static String documentKey(UUID documentId) {
        return "documents/" + documentId + ".pdf";
    }

    /** Renders PDF from arbitrary layout JSON (e.g. editor preview / local state). */
    @Transactional(readOnly = true)
    public byte[] renderPreviewPdf(JsonNode layout, JsonNode data, UUID orgId) {
        if (layout == null || layout.isNull()) {
            throw new com.agreemint.api.BadRequestException("layout is required");
        }
        templateVersionService.assertValidLayout(layout);
        if (data == null || data.isNull()) {
            data = JsonNodeFactory.instance.objectNode();
        }
        try {
            // Preview must match what generation produces — previewing clean
            // and then getting a watermark would be a nasty surprise.
            return pdfRendererService.render(layout, data, planGate.isFreeRestricted(orgId));
        } catch (IOException e) {
            log.error("Preview PDF generation failed (I/O)", e);
            throw new com.agreemint.api.BadRequestException(
                    "PDF generation failed: " + e.getMessage());
        }
    }

    /** Back-compat: unauthenticated callers / tests default to the UI path. */
    @Transactional
    public GenerateResponse generate(GenerateRequest request) {
        return generate(request, null, null, DocumentSource.UI_GENERATED);
    }

    /** UI-path convenience overload — preserves the DRAFT-lifecycle default. */
    @Transactional
    public GenerateResponse generate(GenerateRequest request, UUID userId, UUID orgId) {
        return generate(request, userId, orgId, DocumentSource.UI_GENERATED);
    }

    /**
     * Main generate implementation. API-sourced documents skip the
     * lifecycle entirely — the consuming company owns review/approval
     * on their side, so carrying a status like DRAFT that never progresses
     * would just be misleading in our UI.
     */
    @Transactional
    public GenerateResponse generate(GenerateRequest request, UUID userId, UUID orgId, DocumentSource source) {
        TemplateVersion version = templateVersionService.getVersionEntity(
                request.templateId(), request.versionId());
        JsonNode data = request.data();
        if (data == null || data.isNull()) {
            data = JsonNodeFactory.instance.objectNode();
        }

        // Where the document is filed. Caller's org wins, as it always has.
        UUID effectiveOrgId = orgId != null ? orgId : version.getTemplate().getOrgId();

        // Which org's plan and allowance govern this render — deliberately NOT
        // effectiveOrgId. The caller's org id arrives from the X-Org-Id header,
        // which JwtAuthenticationFilter accepts as any well-formed UUID, so it is
        // client-controlled. Charging it would let a member of a capped org bill
        // their document to an uncapped workspace (or drain a third party's
        // allowance) by changing one header, and would let a free workspace's
        // template render without its watermark the same way. The template's own
        // org is server-derived, so it is the safe anchor for both.
        UUID governingOrgId = version.getTemplate().getOrgId() != null
                ? version.getTemplate().getOrgId()
                : effectiveOrgId;

        // Charged before the row is written: a rejected generation should leave
        // no PENDING document behind that never completes. Refunded below if the
        // work it paid for fails.
        pdfQuota.requireHeadroom(governingOrgId);

        GeneratedDocument doc = new GeneratedDocument();
        doc.setTemplate(version.getTemplate());
        doc.setVersion(version);
        doc.setInputData(data);
        doc.setStatus(DocumentStatus.PENDING);
        doc.setSource(source == null ? DocumentSource.UI_GENERATED : source);
        // UI docs start DRAFT; API docs have no lifecycle — they're terminal.
        doc.setLifecycleStatus(doc.getSource() == DocumentSource.API_GENERATED
                ? null
                : com.agreemint.domain.LifecycleStatus.DRAFT);
        if (userId != null) doc.setCreatedBy(userId);
        if (effectiveOrgId != null) doc.setOrgId(effectiveOrgId);
        generatedDocumentRepository.save(doc);
        generatedDocumentRepository.flush();

        try {
            // Free-plan documents carry a watermark. Anchored to the template's
            // org for the same reason the quota is — see governingOrgId above.
            boolean watermark = planGate.isFreeRestricted(governingOrgId);
            byte[] pdf = pdfRendererService.render(version.getLayoutJson(), data, watermark);
            r2.putDocument(documentKey(doc.getId()), pdf, "application/pdf");
            // `fileUrl` stays as our own routing endpoint — the controller
            // redirects to a fresh presigned R2 URL on each hit, so the
            // column doesn't need to know the bucket / account layout.
            doc.setFileUrl("/api/documents/" + doc.getId() + "/file");
            doc.setStatus(DocumentStatus.COMPLETED);
        } catch (RuntimeException | IOException e) {
            // The allowance paid for a document that does not exist. Without the
            // refund an R2 outage silently burns a capped customer's whole day
            // and leaves them nothing to show for it.
            pdfQuota.refund(governingOrgId);
            log.error("Document PDF generation failed for doc {}", doc.getId(), e);
            doc.setStatus(DocumentStatus.FAILED);
            doc.setFileUrl(null);
            generatedDocumentRepository.save(doc);
            if (e instanceof RuntimeException re) throw re;
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

    /**
     * Resolve a document id to a short-TTL presigned R2 URL. Used by the
     * API-key ({@code /api/v1/*}) downloads where 302-redirecting is fine —
     * server-to-server clients follow redirects without CORS preflights.
     */
    @Transactional(readOnly = true)
    public URL resolvePresignedUrl(UUID documentId) {
        assertDownloadable(documentId);
        return r2.presignDocumentGet(documentKey(documentId));
    }

    /**
     * Open a read stream to the stored PDF. The browser-facing
     * {@code /api/documents/{id}/file} endpoint uses this to proxy bytes so
     * the response is same-origin and no CORS preflight hits R2.
     */
    @Transactional(readOnly = true)
    public ResponseInputStream<GetObjectResponse> openDocumentStream(UUID documentId) {
        assertDownloadable(documentId);
        return r2.openDocument(documentKey(documentId));
    }

    private void assertDownloadable(UUID documentId) {
        GeneratedDocument d = generatedDocumentRepository.findById(documentId)
                .orElseThrow(() -> new com.agreemint.api.NotFoundException("Document not found"));
        if (d.getStatus() != DocumentStatus.COMPLETED || d.getFileUrl() == null) {
            throw new com.agreemint.api.NotFoundException("PDF not available");
        }
    }
}
