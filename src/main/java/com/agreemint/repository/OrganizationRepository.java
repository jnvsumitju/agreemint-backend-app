package com.agreemint.repository;

import com.agreemint.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    Optional<Organization> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /**
     * Admin list search. {@code q} must never be null — pass "" for "no filter".
     *
     * <p>The obvious {@code :q IS NULL OR LOWER(col) LIKE ...} form fails on
     * Postgres: a null bind has no type context other than {@code LOWER(?)},
     * PgJDBC sends it untyped, the server infers {@code bytea}, and there is no
     * {@code lower(bytea)} — so the unfiltered list, which is the default view,
     * threw 500 while a search worked. Comparing against '' keeps the parameter
     * a real string, which is all Postgres needs to type it.
     *
     * <p>H2 in PostgreSQL mode accepts the null form, which is why the tests
     * were green and only production failed.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT o FROM Organization o
            WHERE :q = ''
               OR LOWER(o.name) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(o.slug) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    org.springframework.data.domain.Page<Organization> search(
            @org.springframework.data.repository.query.Param("q") String q,
            org.springframework.data.domain.Pageable pageable);
}
