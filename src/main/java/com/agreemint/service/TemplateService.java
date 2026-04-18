package com.agreemint.service;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.CreateTemplateRequest;
import com.agreemint.api.dto.TemplateResponse;
import com.agreemint.domain.Product;
import com.agreemint.domain.Template;
import com.agreemint.repository.ProductRepository;
import com.agreemint.repository.TemplateRepository;
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

    @Transactional(readOnly = true)
    public List<TemplateResponse> listAll() {
        return listFiltered(null);
    }

    /**
     * List templates, optionally narrowed to one product. Org scoping is
     * enforced upstream by {@code OrgAuthorizationService} via the controller.
     */
    @Transactional(readOnly = true)
    public List<TemplateResponse> listFiltered(UUID productId) {
        List<Template> rows = productId != null
                ? templateRepository.findByProductIdOrderByCreatedAtDesc(productId)
                : templateRepository.findAll();
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
