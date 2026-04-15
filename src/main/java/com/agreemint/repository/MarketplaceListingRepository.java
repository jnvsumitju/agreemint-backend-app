package com.agreemint.repository;

import com.agreemint.domain.MarketplaceListing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MarketplaceListingRepository extends JpaRepository<MarketplaceListing, UUID> {

    List<MarketplaceListing> findByPublishedTrueOrderByCreatedAtDesc();

    List<MarketplaceListing> findByCategoryAndPublishedTrue(String category);
}
