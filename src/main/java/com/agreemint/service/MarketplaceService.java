package com.agreemint.service;

import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.MarketplaceListingResponse;
import com.agreemint.domain.MarketplaceListing;
import com.agreemint.domain.Template;
import com.agreemint.repository.MarketplaceListingRepository;
import com.agreemint.repository.TemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class MarketplaceService {

    private final MarketplaceListingRepository listingRepo;
    private final TemplateRepository templateRepo;

    public MarketplaceService(MarketplaceListingRepository listingRepo,
                              TemplateRepository templateRepo) {
        this.listingRepo = listingRepo;
        this.templateRepo = templateRepo;
    }

    @Transactional(readOnly = true)
    public List<MarketplaceListingResponse> listPublished() {
        return listingRepo.findByPublishedTrueOrderByCreatedAtDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MarketplaceListingResponse> listByCategory(String category) {
        return listingRepo.findByCategoryAndPublishedTrue(category)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public MarketplaceListingResponse getById(UUID id) {
        MarketplaceListing listing = listingRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Marketplace listing not found"));
        return toResponse(listing);
    }

    @Transactional
    public MarketplaceListingResponse publish(UUID authorId, String authorName, UUID orgId,
                                               UUID sourceTemplateId, String title,
                                               String description, String category, String tags) {
        MarketplaceListing listing = new MarketplaceListing();
        listing.setType("TEMPLATE");
        listing.setTitle(title);
        listing.setDescription(description);
        listing.setAuthorId(authorId);
        listing.setAuthorName(authorName);
        listing.setOrgId(orgId);
        listing.setSourceTemplateId(sourceTemplateId);
        listing.setCategory(category);
        listing.setTags(tags);
        listing.setPublished(true);
        listingRepo.save(listing);
        return toResponse(listing);
    }

    @Transactional
    public Template cloneTemplate(UUID listingId, UUID targetOrgId, UUID ownerId) {
        MarketplaceListing listing = listingRepo.findById(listingId)
                .orElseThrow(() -> new NotFoundException("Marketplace listing not found"));

        Template source = templateRepo.findById(listing.getSourceTemplateId())
                .orElseThrow(() -> new NotFoundException("Source template not found"));

        Template clone = new Template();
        clone.setName(source.getName());
        clone.setOrgId(targetOrgId);
        clone.setOwnerId(ownerId);
        clone.setCreatedBy(source.getCreatedBy());
        templateRepo.save(clone);

        // Increment install count
        listing.setInstallCount(listing.getInstallCount() + 1);
        listing.setUpdatedAt(Instant.now());
        listingRepo.save(listing);

        return clone;
    }

    private MarketplaceListingResponse toResponse(MarketplaceListing m) {
        return new MarketplaceListingResponse(
                m.getId(),
                m.getType(),
                m.getTitle(),
                m.getDescription(),
                m.getAuthorName(),
                m.getThumbnailUrl(),
                m.getCategory(),
                m.getTags(),
                m.getInstallCount(),
                m.getCreatedAt()
        );
    }
}
