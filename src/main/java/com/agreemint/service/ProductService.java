package com.agreemint.service;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.ProductMetricsResponse;
import com.agreemint.domain.DocumentSource;
import com.agreemint.domain.Product;
import com.agreemint.repository.GeneratedDocumentRepository;
import com.agreemint.repository.ProductRepository;
import com.agreemint.repository.TemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD for products. Write operations assume the caller has already
 * confirmed ADMIN role on the target org — see {@code ProductController}.
 */
@Service
public class ProductService {

    private final ProductRepository productRepo;
    private final TemplateRepository templateRepo;
    private final GeneratedDocumentRepository docRepo;

    public ProductService(ProductRepository productRepo,
                          TemplateRepository templateRepo,
                          GeneratedDocumentRepository docRepo) {
        this.productRepo = productRepo;
        this.templateRepo = templateRepo;
        this.docRepo = docRepo;
    }

    @Transactional(readOnly = true)
    public List<Product> list(UUID orgId) {
        return productRepo.findByOrgIdOrderByNameAsc(orgId);
    }

    @Transactional(readOnly = true)
    public Product get(UUID productId) {
        return productRepo.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product not found"));
    }

    @Transactional
    public Product create(UUID orgId, UUID createdBy, String name, String description) {
        String normalised = name == null ? "" : name.trim();
        if (normalised.isEmpty()) throw new BadRequestException("Product name is required");
        if (productRepo.existsByOrgIdAndName(orgId, normalised)) {
            throw new BadRequestException("A product named \"" + normalised + "\" already exists");
        }
        Product p = new Product();
        p.setOrgId(orgId);
        p.setName(normalised);
        p.setDescription(description == null ? null : description.trim());
        p.setCreatedBy(createdBy);
        return productRepo.save(p);
    }

    @Transactional
    public Product rename(UUID productId, UUID orgId, String newName, String newDescription) {
        Product p = productRepo.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product not found"));
        if (!p.getOrgId().equals(orgId)) {
            // Cross-org rename attempts look the same as "does not exist" to
            // avoid leaking product names across tenants.
            throw new NotFoundException("Product not found");
        }
        String normalised = newName == null ? p.getName() : newName.trim();
        if (normalised.isEmpty()) throw new BadRequestException("Product name is required");
        if (!normalised.equalsIgnoreCase(p.getName())
                && productRepo.existsByOrgIdAndName(orgId, normalised)) {
            throw new BadRequestException("A product named \"" + normalised + "\" already exists");
        }
        p.setName(normalised);
        if (newDescription != null) p.setDescription(newDescription.trim());
        p.setUpdatedAt(Instant.now());
        return productRepo.save(p);
    }

    /** Assert a product id belongs to the given org. Used by template create /
     *  update paths so a designer can't sneak a cross-org product in. */
    @Transactional(readOnly = true)
    public Product assertBelongsToOrg(UUID productId, UUID orgId) {
        Product p = productRepo.findById(productId)
                .orElseThrow(() -> new BadRequestException("Product not found"));
        if (!p.getOrgId().equals(orgId)) {
            throw new BadRequestException("Product not found");
        }
        return p;
    }

    /**
     * Per-product metrics for the Products page. Two aggregate queries
     * (templates grouped by product, documents grouped by product+source)
     * keep this at O(1) round trips regardless of product count.
     */
    @Transactional(readOnly = true)
    public List<ProductMetricsResponse> listWithMetrics(UUID orgId) {
        List<Product> products = productRepo.findByOrgIdOrderByNameAsc(orgId);

        Map<UUID, Long> templateCounts = new HashMap<>();
        for (Object[] row : templateRepo.countTemplatesGroupedByProduct(orgId)) {
            templateCounts.put((UUID) row[0], ((Number) row[1]).longValue());
        }

        // Document aggregates: rows are (productId, source, count, maxCreatedAt).
        Map<UUID, long[]> docCounts = new HashMap<>();       // [ui, api]
        Map<UUID, Instant> lastDocAt = new HashMap<>();
        for (Object[] row : docRepo.aggregateDocsByProduct(orgId)) {
            UUID pid = (UUID) row[0];
            DocumentSource src = (DocumentSource) row[1];
            long count = ((Number) row[2]).longValue();
            Instant latest = (Instant) row[3];
            long[] split = docCounts.computeIfAbsent(pid, k -> new long[2]);
            if (src == DocumentSource.UI_GENERATED) split[0] += count;
            else if (src == DocumentSource.API_GENERATED) split[1] += count;
            Instant existing = lastDocAt.get(pid);
            if (latest != null && (existing == null || latest.isAfter(existing))) {
                lastDocAt.put(pid, latest);
            }
        }

        List<ProductMetricsResponse> out = new ArrayList<>(products.size());
        for (Product p : products) {
            long[] split = docCounts.getOrDefault(p.getId(), new long[2]);
            out.add(new ProductMetricsResponse(
                    p.getId(),
                    p.getName(),
                    p.getDescription(),
                    templateCounts.getOrDefault(p.getId(), 0L),
                    split[0] + split[1],
                    split[0],
                    split[1],
                    lastDocAt.get(p.getId()),
                    p.getCreatedAt()));
        }
        return out;
    }
}
