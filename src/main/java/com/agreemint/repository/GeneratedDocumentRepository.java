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

    long countByOrgIdAndLifecycleStatus(UUID orgId, LifecycleStatus status);

    long countByOrgIdAndSource(UUID orgId, DocumentSource source);
}
