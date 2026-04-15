package com.agreemint.repository;

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

    List<GeneratedDocument> findByExpiresAtBeforeAndLifecycleStatus(Instant before, LifecycleStatus status);

    long countByOrgIdAndLifecycleStatus(UUID orgId, LifecycleStatus status);
}
