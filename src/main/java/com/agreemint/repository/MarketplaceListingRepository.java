package com.agreemint.repository;

import com.agreemint.domain.MarketplaceListing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MarketplaceListingRepository extends JpaRepository<MarketplaceListing, UUID> {

    List<MarketplaceListing> findByPublishedTrueOrderByCreatedAtDesc();

    /** Official rows only — what a FREE-plan org is allowed to browse. */
    List<MarketplaceListing> findByPublishedTrueAndOfficialTrueOrderByCreatedAtDesc();

    List<MarketplaceListing> findByCategoryAndPublishedTrueAndOfficialTrue(String category);

    List<MarketplaceListing> findByCategoryAndPublishedTrue(String category);

    /** Is this template already live in the catalogue? */
    boolean existsBySourceTemplateIdAndPublishedTrue(UUID sourceTemplateId);

    /** Re-seeding refreshes the existing listing in place rather than adding a second. */
    java.util.Optional<MarketplaceListing> findFirstBySourceTemplateId(UUID sourceTemplateId);

    /** Every listing an org has published, withdrawn ones included. */
    List<MarketplaceListing> findByOrgIdOrderByCreatedAtDesc(UUID orgId);
}
