package com.agreemint.service;

import com.agreemint.api.dto.GenerateRequest;
import com.agreemint.api.dto.GenerateResponse;
import com.agreemint.api.dto.GeneratedDocumentResponse;
import com.agreemint.domain.DocumentSource;
import com.agreemint.domain.DocumentStatus;
import com.agreemint.domain.DocumentReceipt;
import com.agreemint.domain.GeneratedDocument;
import com.agreemint.domain.Template;
import com.agreemint.domain.TemplateStatus;
import com.agreemint.api.BadRequestException;
import com.agreemint.domain.TemplateVersion;
import com.agreemint.pdf.PdfRendererService;
import com.agreemint.pdf.PdfSigningService;
import com.agreemint.pdf.VerificationMark;
import com.agreemint.security.HashUtils;
import com.agreemint.repository.DocumentReceiptRepository;
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
import java.time.Instant;
import java.util.UUID;

@Service
public class DocumentGenerationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentGenerationService.class);

    private final TemplateVersionService templateVersionService;
    private final GeneratedDocumentRepository generatedDocumentRepository;
    private final DocumentReceiptRepository documentReceiptRepository;
    private final PdfRendererService pdfRendererService;
    private final PdfSigningService pdfSigningService;
    private final R2StorageService r2;
    private final WebhookService webhookService;
    private final com.agreemint.billing.PlanGate planGate;
    private final com.agreemint.billing.PdfQuotaService pdfQuota;

    public DocumentGenerationService(
            TemplateVersionService templateVersionService,
            GeneratedDocumentRepository generatedDocumentRepository,
            DocumentReceiptRepository documentReceiptRepository,
            PdfRendererService pdfRendererService,
            PdfSigningService pdfSigningService,
            R2StorageService r2,
            WebhookService webhookService,
            com.agreemint.billing.PlanGate planGate,
            com.agreemint.billing.PdfQuotaService pdfQuota) {
        this.templateVersionService = templateVersionService;
        this.generatedDocumentRepository = generatedDocumentRepository;
        this.documentReceiptRepository = documentReceiptRepository;
        this.pdfRendererService = pdfRendererService;
        this.pdfSigningService = pdfSigningService;
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

    /**
     * Render for a signed-out visitor in the /try sandbox. Always watermarked.
     *
     * <p>A separate method rather than a flag on {@link #renderPreviewPdf}, and
     * that is the whole point of it. {@code PlanGate.isFreeRestricted(null)}
     * returns {@code false} on its first line, so an anonymous caller passing a
     * null org through the ordinary preview path receives a CLEAN pdf — better
     * than the watermarked one a real free-plan customer gets for signing up.
     * Passing {@code true} literally here makes that impossible to get wrong by
     * forgetting an argument.
     *
     * <p>Watermarked is also the honest product answer: the visitor gets a real
     * document proving the thing works, and removing the mark is the reason to
     * create an account. Giving away the clean artifact would leave no reason.
     */
    public byte[] renderAnonymousSandboxPdf(JsonNode layout, JsonNode data) {
        if (layout == null || layout.isNull()) {
            throw new com.agreemint.api.BadRequestException("layout is required");
        }
        templateVersionService.assertValidLayout(layout);
        if (data == null || data.isNull()) {
            data = JsonNodeFactory.instance.objectNode();
        }
        try {
            return pdfRendererService.render(layout, data, true);
        } catch (IOException e) {
            log.error("Anonymous sandbox PDF generation failed (I/O)", e);
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

        // Lifecycle gate. Deliberately here, in the single overload both the
        // console and the v1 public API funnel through, rather than in each
        // controller — a check duplicated per entry point is a check that gets
        // forgotten when the next entry point is added.
        //
        // Note this gates GENERATION only. Preview stays open in every state:
        // rendering a draft is how you get it finished, and blocking that would
        // make DRAFT a state nobody could leave.
        Template tpl = version.getTemplate();
        if (tpl.getStatus() == null || !tpl.getStatus().allowsGeneration()) {
            throw new BadRequestException(
                    "This template is " + (tpl.getStatus() == null ? "not active" : tpl.getStatus().name().toLowerCase())
                            + " — only active templates can generate documents."
                            + (tpl.getStatus() == TemplateStatus.ARCHIVED
                                ? " Restore it to use it again."
                                : " Set it to Active when it is ready."));
        }

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

        // Assigned inside the try, read after it. Declared here so a FAILED
        // generation leaves them null and no receipt is written — a receipt for
        // a document that was never handed out would be a claim we cannot
        // support.
        String sha256 = null;
        long byteSize = 0L;

        // Every document gets a code, whether or not it is printed. It costs a
        // stored random value, and it means turning the visible mark on for a
        // template later needs no backfill for the documents already issued.
        String verificationCode = VerificationCodes.generate();

        // Read from the committed layout, so the choice travels with the
        // template version rather than following the template's current
        // settings — regenerating an old version must not silently start
        // stamping a mark that version never had.
        boolean visibleMark = version.getLayoutJson() != null
                && version.getLayoutJson().path("page").path("verificationMark").asBoolean(false);

        try {
            // Free-plan documents carry a watermark. Anchored to the template's
            // org for the same reason the quota is — see governingOrgId above.
            boolean watermark = planGate.isFreeRestricted(governingOrgId);
            byte[] rendered = pdfRendererService.render(
                    version.getLayoutJson(), data, watermark,
                    new VerificationMark(doc.getId(), verificationCode, visibleMark));

            // Sign BEFORE hashing. A PAdES signature rewrites the file, so a
            // digest taken first would describe bytes that never left the
            // building — every issued document would fail its own verification.
            // Returns the input unchanged when signing is disabled or fails.
            byte[] pdf = pdfSigningService.sign(rendered);

            // Fingerprint the bytes here, before anything else touches them.
            // This is the only point where the complete PDF exists in one
            // place, and — importantly — it is byte-for-byte what the caller
            // receives: the browser endpoint streams the stored object straight
            // through and the API endpoint presigns it, so neither rewrites a
            // single byte. A digest taken here is therefore one the recipient
            // can reproduce from the file in their hands.
            sha256 = HashUtils.sha256(pdf);
            byteSize = pdf.length;

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

        // The tamper-evidence record. Same transaction as the document, so a
        // rolled-back generation cannot leave a receipt behind claiming we
        // issued something we did not.
        if (sha256 != null) {
            DocumentReceipt receipt = new DocumentReceipt();
            receipt.setDocumentId(doc.getId());
            receipt.setOrgId(doc.getOrgId());
            receipt.setTemplateId(doc.getTemplate().getId());
            receipt.setVersionId(doc.getVersion().getId());
            receipt.setSha256(sha256);
            receipt.setVerificationCode(verificationCode);
            receipt.setByteSize(byteSize);
            receipt.setIssuedAt(Instant.now());
            documentReceiptRepository.save(receipt);
        }

        // Webhook emit — fire-and-forget (persisted as PENDING; dispatcher picks up).
        UUID payloadOrgId = doc.getOrgId();
        if (payloadOrgId != null) {
            // `sha256` rides along on the existing, already-HMAC-signed event.
            // That hands integrators an out-of-band copy of the digest they can
            // authenticate independently — so they are not relying on the same
            // channel that delivered the file. Adding a field to a known event
            // needs no change to WebhookService.KNOWN_EVENTS.
            webhookService.emit(payloadOrgId, "document.generated", java.util.Map.of(
                    "documentId", doc.getId().toString(),
                    "templateId", doc.getTemplate().getId().toString(),
                    "versionId", doc.getVersion().getId().toString(),
                    "status", doc.getStatus().name(),
                    "fileUrl", doc.getFileUrl() == null ? "" : doc.getFileUrl(),
                    "sha256", sha256 == null ? "" : sha256,
                    "createdAt", doc.getCreatedAt() == null ? "" : doc.getCreatedAt().toString()
            ));
        }

        return new GenerateResponse(doc.getId(), doc.getFileUrl(), sha256);
    }

    @Transactional(readOnly = true)
    public GeneratedDocumentResponse getDocument(UUID id, UUID actingOrgId) {
        GeneratedDocument d = loadInOrg(id, actingOrgId);
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
    public URL resolvePresignedUrl(UUID documentId, UUID actingOrgId) {
        // The v1 controller already calls assertSameOrg before reaching here.
        // Checking again inside the service is deliberate: the tenant guard
        // belongs with the data access, not only at one of its call sites.
        assertDownloadable(loadInOrg(documentId, actingOrgId));
        return r2.presignDocumentGet(documentKey(documentId));
    }

    /**
     * Open a read stream to the stored PDF. The browser-facing
     * {@code /api/documents/{id}/file} endpoint uses this to proxy bytes so
     * the response is same-origin and no CORS preflight hits R2.
     */
    @Transactional(readOnly = true)
    public ResponseInputStream<GetObjectResponse> openDocumentStream(UUID documentId, UUID actingOrgId) {
        assertDownloadable(loadInOrg(documentId, actingOrgId));
        return r2.openDocument(documentKey(documentId));
    }

    /**
     * Load a document, or behave as though it does not exist for anyone outside
     * the owning workspace.
     *
     * <p>This existed only on the API-key surface ({@code assertSameOrg} in
     * {@code PublicApiController}); the browser-facing
     * {@code /api/documents/**} endpoints took a bare {@code UUID} and did a
     * plain {@code findById}. Since {@code /api/**} only requires *a* valid
     * session, any signed-in user of any workspace who came by a document id
     * could read another tenant's metadata and stream their PDF — and the id is
     * not a secret: it appears in {@code fileUrl}, in the R2 object key, in the
     * {@code Content-Disposition} filename, and in the {@code document.generated}
     * webhook payload.
     *
     * <p>404 rather than 403, deliberately: a 403 would confirm the document
     * exists, which is the fact being protected.
     */
    private GeneratedDocument loadInOrg(UUID documentId, UUID actingOrgId) {
        GeneratedDocument d = generatedDocumentRepository.findById(documentId)
                .orElseThrow(() -> new com.agreemint.api.NotFoundException("Document not found"));
        if (actingOrgId == null || !actingOrgId.equals(d.getOrgId())) {
            throw new com.agreemint.api.NotFoundException("Document not found");
        }
        return d;
    }

    private void assertDownloadable(GeneratedDocument d) {
        if (d.getStatus() != DocumentStatus.COMPLETED || d.getFileUrl() == null) {
            throw new com.agreemint.api.NotFoundException("PDF not available");
        }
    }
}
