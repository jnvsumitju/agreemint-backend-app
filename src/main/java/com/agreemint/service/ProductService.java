package com.agreemint.service;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.domain.Product;
import com.agreemint.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for products. Write operations assume the caller has already
 * confirmed ADMIN role on the target org — see {@code ProductController}.
 */
@Service
public class ProductService {

    private final ProductRepository productRepo;

    public ProductService(ProductRepository productRepo) {
        this.productRepo = productRepo;
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
}
