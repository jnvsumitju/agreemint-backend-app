package com.agreemint.repository;

import com.agreemint.domain.ActivityLog;
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
}
