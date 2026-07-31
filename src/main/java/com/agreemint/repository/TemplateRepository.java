package com.agreemint.repository;

import com.agreemint.domain.Template;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TemplateRepository extends JpaRepository<Template, UUID> {

    List<Template> findByOrgId(UUID orgId);

    /** Used to enforce the free-plan template cap. */
    long countByOrgId(UUID orgId);

    List<Template> findByOwnerId(UUID ownerId);

    /** All templates belonging to an org, plus legacy unowned templates. */
    List<Template> findByOrgIdOrOrgIdIsNull(UUID orgId);

    /** Org-scoped list, newest first. Used by the Templates page. */
    List<Template> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    /** Org-scoped templates assigned to a specific product. The org check
     *  is defence-in-depth on top of the product's own org scoping. */
    List<Template> findByOrgIdAndProductIdOrderByCreatedAtDesc(UUID orgId, UUID productId);

    /** @deprecated not org-scoped; retained only for callers that have
     *     already verified org context. Prefer
     *     {@link #findByOrgIdAndProductIdOrderByCreatedAtDesc}. */
    @Deprecated
    List<Template> findByProductIdOrderByCreatedAtDesc(UUID productId);

    /** Count templates per product, org-scoped. Used by the Products page
     *  metrics. {@code null} product_id rows are filtered out. */
    @org.springframework.data.jpa.repository.Query(
            "select t.productId, count(t) from Template t "
          + "where t.orgId = :orgId and t.productId is not null "
          + "group by t.productId")
    List<Object[]> countTemplatesGroupedByProduct(
            @org.springframework.data.repository.query.Param("orgId") UUID orgId);

    /** Template counts for a batch of orgs — one query for the whole page. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT t.orgId, COUNT(t)
            FROM Template t
            WHERE t.orgId IN :orgIds
            GROUP BY t.orgId
            """)
    List<Object[]> countByOrgIds(
            @org.springframework.data.repository.query.Param("orgIds") java.util.Collection<UUID> orgIds);
}
