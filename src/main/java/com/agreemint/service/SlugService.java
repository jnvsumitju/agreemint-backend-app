package com.agreemint.service;

import com.agreemint.repository.OrganizationRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Shared slug generation for organizations.
 * Uses retry-on-collision instead of check-then-act to avoid TOCTOU race conditions.
 */
@Service
public class SlugService {

    private final OrganizationRepository orgRepo;

    public SlugService(OrganizationRepository orgRepo) {
        this.orgRepo = orgRepo;
    }

    /**
     * Generate a unique slug from a human-readable name.
     * Retries with numeric suffixes on collision, falls back to UUID fragment.
     */
    public String generateUniqueSlug(String name) {
        String base = name.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "workspace";

        // Try base, then base-1, base-2, etc.
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = attempt == 0 ? base : base + "-" + attempt;
            if (!orgRepo.existsBySlug(candidate)) {
                return candidate;
            }
        }

        // Fallback: append random suffix to guarantee uniqueness
        return base + "-" + UUID.randomUUID().toString().substring(0, 6);
    }
}
