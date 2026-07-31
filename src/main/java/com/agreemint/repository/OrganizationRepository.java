package com.agreemint.repository;

import com.agreemint.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /** Admin org list: optional name/slug substring, paged and sorted in the DB. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT o FROM Organization o
            WHERE :q IS NULL
               OR LOWER(o.name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(o.slug) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    org.springframework.data.domain.Page<Organization> search(
            @org.springframework.data.repository.query.Param("q") String q,
            org.springframework.data.domain.Pageable pageable);
}
