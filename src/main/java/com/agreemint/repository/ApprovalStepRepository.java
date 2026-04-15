package com.agreemint.repository;

import com.agreemint.domain.ApprovalStatus;
import com.agreemint.domain.ApprovalStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalStepRepository extends JpaRepository<ApprovalStep, UUID> {

    List<ApprovalStep> findByWorkflowIdOrderByStepOrderAsc(UUID workflowId);

    List<ApprovalStep> findByAssigneeIdAndStatus(UUID assigneeId, ApprovalStatus status);

    Optional<ApprovalStep> findFirstByWorkflowIdAndStatusOrderByStepOrderAsc(
            UUID workflowId, ApprovalStatus status);
}
