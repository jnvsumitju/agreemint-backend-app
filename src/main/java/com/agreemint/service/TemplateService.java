package com.agreemint.service;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.CreateTemplateRequest;
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
import java.util.UUID;

@Service
public class TemplateService {

    private static final Logger log = LoggerFactory.getLogger(TemplateService.class);

    private final TemplateRepository templateRepository;
    private final ProductService productService;
    private final ProductRepository productRepository;

    public TemplateService(TemplateRepository templateRepository,
                           ProductService productService,
                           ProductRepository productRepository) {
        this.templateRepository = templateRepository;
        this.productService = productService;
        this.productRepository = productRepository;
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
        return rows.stream()
                .map(t -> toResponse(t, productNames.get(t.getProductId())))
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
        if (productIds.isEmpty()) return Map.of();
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
        return toResponse(t, productName);
    }

    private TemplateResponse toResponse(Template t, String productName) {
        return new TemplateResponse(
                t.getId(),
                t.getName(),
                t.getCreatedBy(),
                t.getCreatedAt(),
                t.getProductId(),
                productName);
    }
}
