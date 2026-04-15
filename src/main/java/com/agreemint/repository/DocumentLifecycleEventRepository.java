package com.agreemint.repository;

import com.agreemint.domain.DocumentLifecycleEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentLifecycleEventRepository extends JpaRepository<DocumentLifecycleEvent, UUID> {

    List<DocumentLifecycleEvent> findByDocumentIdOrderByCreatedAtDesc(UUID documentId);

    List<DocumentLifecycleEvent> findByDocumentIdOrderByCreatedAtDesc(UUID documentId, Pageable pageable);
}
