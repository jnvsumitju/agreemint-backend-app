package com.agreemint.repository;

import com.agreemint.domain.Template;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TemplateRepository extends JpaRepository<Template, UUID> {

    List<Template> findByOrgId(UUID orgId);

    /** Stable identity for re-seeding the first-party catalogue. */
    java.util.Optional<Template> findFirstByOrgIdAndName(UUID orgId, String name);

    /** Used to enforce the free-plan template cap. */
    long countByOrgId(UUID orgId);

    /**
     * Record a freshly rendered preview image, without touching the rest of the row.
     *
     * <p>A targeted UPDATE rather than loading the entity and calling save(),
     * for two reasons. Template carries an {@code @Version} optimistic lock, and
     * this write now happens on a background thread after the commit
     * transaction has closed — so it races every real edit, and losing that race
     * would throw where the only thing at stake is a thumbnail. It is also not a
     * user edit: bumping the entity version because an image finished rendering
     * would make someone else's concurrent save fail for no reason they could
     * understand.
     *
     * <p>Explicitly {@code @Transactional}, and not decoratively so: Spring Data
     * does NOT extend the repository's class-level transaction to custom query
     * methods. This codebase already shipped a {@code @Modifying} query without
     * it — StaffExportRepository.claim — and it threw
     * {@code TransactionRequiredException} on every single call, so the feature
     * had never completed once. These run from a background thread with no
     * ambient transaction, which is the same situation exactly.
     */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Template t
               set t.thumbnailKey = :thumbnailKey,
                   t.draftThumbnailKey = :draftThumbnailKey,
                   t.thumbnailUpdatedAt = :updatedAt
             where t.id = :id
            """)
    int updateThumbnailKeys(UUID id, String thumbnailKey, String draftThumbnailKey,
                            java.time.Instant updatedAt);

    /** As above, for the in-progress capture: leaves the committed key alone. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("""
            update Template t
               set t.draftThumbnailKey = :draftThumbnailKey,
                   t.thumbnailUpdatedAt = :updatedAt
             where t.id = :id
            """)
    int updateDraftThumbnailKey(UUID id, String draftThumbnailKey, java.time.Instant updatedAt);

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
