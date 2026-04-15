package com.agreemint.repository;

import com.agreemint.domain.ApprovalWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalWorkflowRepository extends JpaRepository<ApprovalWorkflow, UUID> {

    Optional<ApprovalWorkflow> findByDocumentId(UUID documentId);

    List<ApprovalWorkflow> findByOrgId(UUID orgId);
}
