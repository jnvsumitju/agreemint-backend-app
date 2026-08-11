package com.agreemint.repository;

import com.agreemint.domain.DocumentSource;
import com.agreemint.domain.GeneratedDocument;
import com.agreemint.domain.LifecycleStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface GeneratedDocumentRepository extends JpaRepository<GeneratedDocument, UUID> {

    List<GeneratedDocument> findByOrgIdAndLifecycleStatusOrderByCreatedAtDesc(
            UUID orgId, LifecycleStatus status, Pageable pageable);

    List<GeneratedDocument> findByOrgIdOrderByCreatedAtDesc(UUID orgId, Pageable pageable);

    // Source-filtered variants — used by the Documents page's UI/API tabs.
    List<GeneratedDocument> findByOrgIdAndSourceOrderByCreatedAtDesc(
            UUID orgId, DocumentSource source, Pageable pageable);

    List<GeneratedDocument> findByOrgIdAndSourceAndLifecycleStatusOrderByCreatedAtDesc(
            UUID orgId, DocumentSource source, LifecycleStatus status, Pageable pageable);

    List<GeneratedDocument> findByExpiresAtBeforeAndLifecycleStatus(Instant before, LifecycleStatus status);

    /**
     * Documents due to expire inside the lookahead window that have not been
     * warned yet.
     *
     * <p>Excludes API-generated documents deliberately: {@code transitionStatus}
     * refuses lifecycle moves for them, so warning that one is "about to expire"
     * would promise a state change the rest of the service declines to make.
     *
     * <p>Paged, because the first time a workspace back-fills expiry dates this
     * could otherwise load an unbounded list into one transaction.
     */
    List<GeneratedDocument>
        findByLifecycleStatusAndSourceNotAndExpiryWarnedAtIsNullAndExpiresAtBetweenOrderByExpiresAtAsc(
            LifecycleStatus status, DocumentSource excludedSource,
            Instant from, Instant to, Pageable pageable);

    long countByOrgIdAndLifecycleStatus(UUID orgId, LifecycleStatus status);

    long countByOrgIdAndSource(UUID orgId, DocumentSource source);

    /**
     * Per-product document totals with UI/API breakdown and last-generated
     * timestamp. Returns rows of {@code (productId, source, count, maxCreatedAt)}
     * aggregated via the template join. The source column lets the caller
     * split UI vs API totals; grouping also by product means this is one
     * trip to the DB per Products-page render.
     */
    @org.springframework.data.jpa.repository.Query(
            "select t.productId, d.source, count(d), max(d.createdAt) "
          + "from GeneratedDocument d "
          + "  join d.template t "
          + "where d.orgId = :orgId and t.productId is not null "
          + "group by t.productId, d.source")
    List<Object[]> aggregateDocsByProduct(
            @org.springframework.data.repository.query.Param("orgId") UUID orgId);

    /**
     * Document counts per org since an instant — powers docsLast30d on the
     * admin org list, which was previously hardcoded to 0.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT d.orgId, COUNT(d)
            FROM GeneratedDocument d
            WHERE d.orgId IN :orgIds AND d.createdAt >= :since
            GROUP BY d.orgId
            """)
    List<Object[]> countByOrgIdsSince(
            @org.springframework.data.repository.query.Param("orgIds") java.util.Collection<UUID> orgIds,
            @org.springframework.data.repository.query.Param("since") Instant since);

    /** Platform-wide document count since an instant. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT COUNT(d) FROM GeneratedDocument d WHERE d.createdAt >= :since")
    long countTotalSince(
            @org.springframework.data.repository.query.Param("since") Instant since);

    /** Per-org totals since an instant, newest-heavy orgs first at the caller. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT d.orgId, COUNT(d)
            FROM GeneratedDocument d
            WHERE d.createdAt >= :since
            GROUP BY d.orgId
            ORDER BY COUNT(d) DESC
            """)
    List<Object[]> topOrgsSince(
            @org.springframework.data.repository.query.Param("since") Instant since,
            org.springframework.data.domain.Pageable pageable);

    /** Per-day document counts since an instant, as (yyyy-MM-dd, count). */
    @org.springframework.data.jpa.repository.Query("""
            SELECT FUNCTION('to_char', d.createdAt, 'YYYY-MM-DD'), COUNT(d)
            FROM GeneratedDocument d
            WHERE d.createdAt >= :since
            GROUP BY FUNCTION('to_char', d.createdAt, 'YYYY-MM-DD')
            """)
    List<Object[]> countByDaySince(
            @org.springframework.data.repository.query.Param("since") Instant since);
}
