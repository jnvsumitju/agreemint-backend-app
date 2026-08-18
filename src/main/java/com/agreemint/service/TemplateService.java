package com.agreemint.service;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.CreateTemplateRequest;
import com.agreemint.api.dto.CreateVersionRequest;
import com.agreemint.api.dto.TemplateResponse;
import com.agreemint.domain.Product;
import com.agreemint.domain.Template;
import com.agreemint.repository.ProductRepository;
import com.agreemint.repository.TemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

@Service
public class TemplateService {

    private static final Logger log = LoggerFactory.getLogger(TemplateService.class);

    private final TemplateRepository templateRepository;
    private final ProductService productService;
    private final ProductRepository productRepository;
    private final TemplateVersionService templateVersionService;
    private final com.agreemint.repository.TemplateVersionRepository templateVersionRepository;
    private final com.agreemint.repository.TemplateDraftRepository templateDraftRepository;

    public TemplateService(TemplateRepository templateRepository,
                           ProductService productService,
                           ProductRepository productRepository,
                           TemplateVersionService templateVersionService,
                           com.agreemint.repository.TemplateVersionRepository templateVersionRepository,
                           com.agreemint.repository.TemplateDraftRepository templateDraftRepository) {
        this.templateRepository = templateRepository;
        this.productService = productService;
        this.productRepository = productRepository;
        this.templateVersionService = templateVersionService;
        this.templateVersionRepository = templateVersionRepository;
        this.templateDraftRepository = templateDraftRepository;
    }

    /**
     * Legacy overload retained for callers that can't supply ownership yet
     * (e.g. import/clone helpers). Produces a template with no owner/org —
     * {@link com.agreemint.security.OrgAuthorizationService} now rejects
     * access to such orphans rather than treating them as wide-open.
     *
     * @deprecated Use {@link #create(CreateTemplateRequest, UUID, UUID)} so the
     *     template is bound to the creator's org + user.
     */
    @Deprecated
    @Transactional
    public TemplateResponse create(CreateTemplateRequest request) {
        Template t = new Template();
        t.setName(request.name());
        t.setCreatedBy(request.createdBy());
        if (request.productId() != null) t.setProductId(request.productId());
        templateRepository.save(t);
        return toResponse(t);
    }

    /**
     * Create a new template owned by {@code ownerId} and scoped to {@code orgId}.
     * Both are required — without them the authorization layer has nothing to
     * enforce access against. {@code productId} is also required as of the
     * Products feature; the service validates it belongs to the same org to
     * prevent cross-tenant assignment.
     */
    @Transactional
    public TemplateResponse create(CreateTemplateRequest request, UUID orgId, UUID ownerId) {
        if (orgId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No organization context");
        }
        if (request.productId() == null) {
            throw new BadRequestException("productId is required. Create a product from Settings → Products first.");
        }
        productService.assertBelongsToOrg(request.productId(), orgId);

        Template t = new Template();
        t.setName(request.name());
        t.setCreatedBy(request.createdBy());
        t.setOrgId(orgId);
        t.setOwnerId(ownerId);
        t.setProductId(request.productId());
        templateRepository.save(t);

        // Seed v1 with the empty default layout. Reviewers/Viewers default to
        // the latest committed version, so a freshly-created template needs
        // *something* committed or they'd see an empty-state forever until a
        // designer hits Commit. Same transaction as the template insert so a
        // version-write failure rolls the whole creation back.
        templateVersionService.createVersion(t.getId(), new CreateVersionRequest(null, null));

        return toResponse(t);
    }

    /**
     * @deprecated Cross-org list. Retained only because callers that held
     *     an org context were passing through it; new callers MUST use
     *     {@link #listForOrg(UUID, UUID)} so tenants can't see each other's
     *     templates. Falls back to returning only org-less (truly legacy)
     *     templates so the method stays safe by default.
     */
    @Deprecated
    @Transactional(readOnly = true)
    public List<TemplateResponse> listAll() {
        return templateRepository.findAll().stream()
                .filter(t -> t.getOrgId() == null)
                .map(this::toResponse)
                .toList();
    }

    /**
     * List an org's templates, optionally narrowed to one product. The org
     * filter is the primary tenancy boundary — without it, every customer
     * saw every other customer's templates.
     */
    @Transactional(readOnly = true)
    public List<TemplateResponse> listForOrg(UUID orgId, UUID productId) {
        if (orgId == null) return List.of();
        List<Template> rows = productId != null
                ? templateRepository.findByOrgIdAndProductIdOrderByCreatedAtDesc(orgId, productId)
                : templateRepository.findByOrgIdOrderByCreatedAtDesc(orgId);
        Map<UUID, String> productNames = productNameCache(rows);
        VersionStatus status = versionStatus(rows.stream().map(Template::getId).toList());
        return rows.stream()
                .map(t -> toResponse(t, productNames.get(t.getProductId()), status))
                .toList();
    }

    @Transactional(readOnly = true)
    public Template getById(UUID id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Template not found"));
    }

    @Transactional(readOnly = true)
    public TemplateResponse getResponse(UUID id) {
        return toResponse(getById(id));
    }

    /**
     * Hard-delete a template along with every row that references it. The
     * schema has {@code ON DELETE CASCADE} on template_versions, template_drafts,
     * template_reviews, and template_shares (see V1/V2/V7/V13 migrations), so a
     * single repository {@code deleteById} cleans up the fan-out automatically.
     * {@code marketplace_listings.source_template_id} is {@code ON DELETE SET NULL}
     * so listings survive the delete with a null source reference.
     *
     * <p>Authorization is the caller's responsibility — this method trusts that
     * the controller has already gated on the actor's org role (ADMIN/DESIGNER).
     */
    @Transactional
    public void delete(UUID templateId) {
        if (!templateRepository.existsById(templateId)) {
            log.info("template.delete noop id={} (already gone)", templateId);
            throw new NotFoundException("Template not found");
        }
        templateRepository.deleteById(templateId);
        log.info("template.delete ok id={}", templateId);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Batch-load the product names for a page of templates so the response
     *  mapper isn't an N+1 of single-row lookups. */
    private Map<UUID, String> productNameCache(List<Template> rows) {
        var productIds = rows.stream()
                .map(Template::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        // HashMap, not Map.of(): the caller looks up `t.getProductId()`, which
        // is null for any template not filed under a product, and the immutable
        // map throws NullPointerException on a null key rather than returning
        // null. A workspace where NO template belongs to a product therefore
        // took this branch and 500'd the whole list.
        if (productIds.isEmpty()) return new HashMap<>();
        Map<UUID, String> out = new HashMap<>();
        for (Product p : productRepository.findAllById(productIds)) {
            out.put(p.getId(), p.getName());
        }
        return out;
    }

    private TemplateResponse toResponse(Template t) {
        String productName = t.getProductId() == null
                ? null
                : productRepository.findById(t.getProductId()).map(Product::getName).orElse(null);
        var status = versionStatus(List.of(t.getId()));
        return toResponse(t, productName, status);
    }

    /**
     * The v1 API shape: identical fields, {@code productName} deliberately null.
     *
     * <p>Lives here rather than being hand-built in the controller so version
     * and draft state come from one place. Built by hand, the two paths could
     * report different states for the same template — and the API's would be
     * the one nobody notices is wrong.
     */
    @Transactional(readOnly = true)
    public TemplateResponse toPublicResponse(Template t) {
        return toResponse(t, null, versionStatus(List.of(t.getId())));
    }

    private TemplateResponse toResponse(Template t, String productName, VersionStatus status) {
        return new TemplateResponse(
                t.getId(),
                t.getName(),
                t.getCreatedBy(),
                t.getCreatedAt(),
                t.getProductId(),
                productName,
                status.versions().get(t.getId()),
                status.withDraft().contains(t.getId()));
    }

    /**
     * Committed version and draft state for a set of templates, in two queries.
     *
     * @param versions highest committed version per template; absent means never committed
     * @param withDraft templates holding editor changes that are in no version
     */
    private record VersionStatus(Map<UUID, Integer> versions, Set<UUID> withDraft) {}

    private VersionStatus versionStatus(Collection<UUID> templateIds) {
        if (templateIds.isEmpty()) return new VersionStatus(Map.of(), Set.of());
        Map<UUID, Integer> versions = new HashMap<>();
        for (Object[] row : templateVersionRepository.findMaxVersionByTemplateIds(templateIds)) {
            versions.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return new VersionStatus(
                versions,
                Set.copyOf(templateDraftRepository.findTemplateIdsWithDraft(templateIds)));
    }
}
