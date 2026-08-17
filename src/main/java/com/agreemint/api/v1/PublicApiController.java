package com.agreemint.api.v1;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.GenerateRequest;
import com.agreemint.api.dto.GenerateResponse;
import com.agreemint.api.dto.GeneratedDocumentResponse;
import com.agreemint.api.dto.TemplateResponse;
import com.agreemint.api.dto.TemplateVersionResponse;
import com.agreemint.domain.DocumentSource;
import com.agreemint.domain.GeneratedDocument;
import com.agreemint.domain.Template;
import com.agreemint.domain.TemplateVersion;
import com.agreemint.repository.GeneratedDocumentRepository;
import com.agreemint.repository.TemplateRepository;
import com.agreemint.repository.TemplateVersionRepository;
import com.agreemint.security.UserPrincipal;
import com.agreemint.service.DocumentGenerationService;
import com.agreemint.service.TemplateVersionService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URL;
import java.util.List;
import java.util.UUID;

/**
 * Public, API-key-authenticated endpoints under {@code /api/v1/*}. Authenticated
 * by {@code ApiKeyAuthenticationFilter} which sets a {@link UserPrincipal} that
 * carries the key's org + granted scopes. Each handler uses
 * {@code @PreAuthorize("hasAuthority('SCOPE_<name>')")} so the caller must hold
 * the matching scope.
 *
 * <p>Every org-level check compares the requested resource's org to the
 * principal's org so a customer can't reach another tenant's data.
 */
@Tag(name = "Public API v1",
     description = "Customer-facing endpoints authenticated with an X-Api-Key header")
@SecurityRequirement(name = "ApiKeyAuth")
@RestController
@RequestMapping("/api/v1")
public class PublicApiController {

    /** Upper bound on {@code size} for the document listing. */
    private static final int MAX_PAGE_SIZE = 100;
    /** Path DocumentGenerationService persists — the JWT/browser download route. */
    private static final String UI_FILE_PREFIX = "/api/documents/";
    /** Public equivalent, scope-checked for API keys. */
    private static final String V1_FILE_PREFIX = "/api/v1/documents/";

    private final TemplateRepository templateRepo;
    private final TemplateVersionRepository versionRepo;
    private final TemplateVersionService versionService;
    private final DocumentGenerationService docService;
    private final GeneratedDocumentRepository docRepo;

    public PublicApiController(
            TemplateRepository templateRepo,
            TemplateVersionRepository versionRepo,
            TemplateVersionService versionService,
            DocumentGenerationService docService,
            GeneratedDocumentRepository docRepo) {
        this.templateRepo = templateRepo;
        this.versionRepo = versionRepo;
        this.versionService = versionService;
        this.docService = docService;
        this.docRepo = docRepo;
    }

    // ── Generate ─────────────────────────────────────────────────────────────

    @Operation(summary = "Generate a PDF from a template",
               description = "Defaults to the template's latest committed version when versionId is omitted. "
                       + "Returns { documentId, fileUrl }; GET /api/v1/documents/{documentId}/file for the binary.")
    @PostMapping("/templates/{templateId}/generate")
    @PreAuthorize("hasAuthority('SCOPE_documents:generate')")
    public ResponseEntity<GenerateResponse> generate(
            @PathVariable UUID templateId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody PublicGenerateRequest body
    ) {
        Template template = templateRepo.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Template not found"));
        assertSameOrg(principal, template.getOrgId());

        UUID versionId = body != null ? body.versionId() : null;
        if (versionId == null) {
            TemplateVersion latest = versionRepo
                    .findFirstByTemplateOrderByVersionNumberDesc(template)
                    .orElseThrow(() -> new BadRequestException(
                            "Template has no committed versions yet — commit one before generating."));
            versionId = latest.getId();
        }

        JsonNode data = body != null ? body.data() : null;
        GenerateRequest req = new GenerateRequest(templateId, versionId, data);
        // API-sourced: tag so the lifecycle workflow is skipped. Customers who
        // run their own review/approval layer get the bare document + webhook
        // and nothing else from our lifecycle tracking.
        GenerateResponse res = docService.generate(req, principal.userId(), principal.orgId(),
                com.agreemint.domain.DocumentSource.API_GENERATED);
        // The digest is passed straight through: only the fileUrl is rewritten
        // for the public surface. An integrator storing this alongside their own
        // record can prove the file is unaltered later without calling us.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new GenerateResponse(
                        res.documentId(), toPublicFileUrl(res.fileUrl()), res.sha256()));
    }

    // ── Documents ────────────────────────────────────────────────────────────

    @Operation(summary = "Get document status + file URL")
    @GetMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('SCOPE_documents:read')")
    public GeneratedDocumentResponse getDocument(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        UUID docOrg = docRepo.findById(id).map(d -> d.getOrgId()).orElse(null);
        assertSameOrg(principal, docOrg);
        return toPublicResponse(docService.getDocument(id, principal.orgId()));
    }

    @Operation(summary = "List generated documents, newest first",
            description = "Scoped to the API key's organisation. Page through with "
                    + "page/size; size is capped at 100. Filter to API- or UI-generated "
                    + "documents with source=API_GENERATED|UI_GENERATED.")
    @GetMapping("/documents")
    @PreAuthorize("hasAuthority('SCOPE_documents:read')")
    public List<GeneratedDocumentResponse> listDocuments(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) DocumentSource source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID orgId = principal.orgId();
        if (orgId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "API key is not bound to an organisation");
        }
        // Clamp rather than reject: a caller asking for 5000 gets the max page
        // instead of a 400, which keeps naive pagination loops working.
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
        List<GeneratedDocument> docs = source == null
                ? docRepo.findByOrgIdOrderByCreatedAtDesc(orgId, pageable)
                : docRepo.findByOrgIdAndSourceOrderByCreatedAtDesc(orgId, source, pageable);
        return docs.stream().map(PublicApiController::toPublicResponse).toList();
    }

    @Operation(summary = "Download the generated PDF",
            description = "Returns a 302 redirect to a short-TTL presigned URL on " +
                    "object storage. Most HTTP clients follow redirects automatically; " +
                    "pass --location to curl.")
    @GetMapping("/documents/{id}/file")
    @PreAuthorize("hasAuthority('SCOPE_documents:read')")
    public ResponseEntity<Void> downloadDocument(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        UUID docOrg = docRepo.findById(id).map(d -> d.getOrgId()).orElse(null);
        assertSameOrg(principal, docOrg);
        URL presigned = docService.resolvePresignedUrl(id, principal.orgId());
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, presigned.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .build();
    }

    // ── Templates + versions (read) ──────────────────────────────────────────

    @Operation(summary = "Get template metadata")
    @GetMapping("/templates/{id}")
    @PreAuthorize("hasAuthority('SCOPE_templates:read')")
    public TemplateResponse getTemplate(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Template t = templateRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Template not found"));
        assertSameOrg(principal, t.getOrgId());
        // Product name is resolved by TemplateService.getResponse for the JWT
        // path; the public API response surface intentionally omits it — the
        // productId is enough for API consumers, they don't need our human
        // label. Pass null for productName to keep the v1 contract stable.
        return new TemplateResponse(t.getId(), t.getName(), t.getCreatedBy(),
                t.getCreatedAt(), t.getProductId(), null);
    }

    @Operation(summary = "List committed versions of a template, newest first")
    @GetMapping("/templates/{id}/versions")
    @PreAuthorize("hasAuthority('SCOPE_templates:read')")
    public List<TemplateVersionResponse> listVersions(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Template t = templateRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Template not found"));
        assertSameOrg(principal, t.getOrgId());
        return versionService.listVersions(id);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Rewrite a stored {@code fileUrl} onto the public API path.
     *
     * <p>{@link DocumentGenerationService} persists {@code /api/documents/{id}/file}
     * — the JWT/browser route the editor and Documents page download from. API-key
     * callers can't use that path (it isn't scope-checked for them), so the v1
     * surface hands back {@code /api/v1/documents/{id}/file} instead. We rewrite
     * here rather than changing what's stored: the column is read by the browser
     * UI and is already baked into existing rows and past webhook payloads.
     */
    private static String toPublicFileUrl(String storedFileUrl) {
        if (storedFileUrl == null) return null;
        return storedFileUrl.startsWith(UI_FILE_PREFIX)
                ? V1_FILE_PREFIX + storedFileUrl.substring(UI_FILE_PREFIX.length())
                : storedFileUrl;
    }

    private static GeneratedDocumentResponse toPublicResponse(GeneratedDocumentResponse doc) {
        return new GeneratedDocumentResponse(doc.id(), doc.templateId(), doc.versionId(),
                toPublicFileUrl(doc.fileUrl()), doc.status(), doc.createdAt());
    }

    private static GeneratedDocumentResponse toPublicResponse(GeneratedDocument doc) {
        return new GeneratedDocumentResponse(
                doc.getId(),
                doc.getTemplate() == null ? null : doc.getTemplate().getId(),
                doc.getVersion() == null ? null : doc.getVersion().getId(),
                toPublicFileUrl(doc.getFileUrl()),
                doc.getStatus(),
                doc.getCreatedAt());
    }

    private static void assertSameOrg(UserPrincipal principal, UUID resourceOrgId) {
        if (resourceOrgId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Resource has no organisation; API keys cannot access orphaned resources");
        }
        if (!resourceOrgId.equals(principal.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Resource belongs to a different organisation");
        }
    }

    public record PublicGenerateRequest(UUID versionId, JsonNode data) {}
}
