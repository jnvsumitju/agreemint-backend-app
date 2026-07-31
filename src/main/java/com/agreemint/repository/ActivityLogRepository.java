package com.agreemint.repository;

import com.agreemint.domain.ActivityLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, UUID> {

    List<ActivityLog> findByOrgIdOrderByCreatedAtDesc(UUID orgId, Pageable pageable);

    // Template-scoped activity — strictly events on the Template (or one of its
    // versions). Generated-document lifecycle events are deliberately excluded:
    // they belong on the Documents view, not in the template editor's panel.
    // Today nothing writes TEMPLATE / TEMPLATE_VERSION rows, so this query will
    // typically return an empty list until template-direct logging is added;
    // that empty state is the desired behaviour (the editor's right panel
    // should NOT bleed in document lifecycle noise from sibling instances).
    @Query("""
            SELECT a FROM ActivityLog a
            WHERE a.orgId = :orgId
              AND a.entityType IN ('TEMPLATE','TEMPLATE_VERSION')
              AND a.entityId = :templateId
            ORDER BY a.createdAt DESC
            """)
    List<ActivityLog> findByOrgIdAndTemplateIdOrderByCreatedAtDesc(
            @Param("orgId") UUID orgId,
            @Param("templateId") UUID templateId,
            Pageable pageable);

    /**
     * Cross-org audit search for the staff portal.
     *
     * <p>Every filter is optional and applied <strong>in the query</strong>. The
     * admin controller previously fetched the newest N rows globally and then
     * filtered in memory, so a search scoped to one org usually returned nothing
     * — the matching rows were simply not in the window. Filtering before the
     * limit is the whole point of this method.
     *
     * <p>{@code action} matches case-insensitively on a prefix, so "template"
     * finds template.created, template.deleted and so on. Passing a full action
     * name still matches exactly that one.
     */
    @Query("""
            SELECT a FROM ActivityLog a
            WHERE (:orgId IS NULL OR a.orgId = :orgId)
              AND (:userId IS NULL OR a.userId = :userId)
              AND (:action IS NULL OR LOWER(a.action) LIKE LOWER(CONCAT(:action, '%')))
            """)
    Page<ActivityLog> search(
            @Param("orgId") UUID orgId,
            @Param("userId") UUID userId,
            @Param("action") String action,
            Pageable pageable);
}
