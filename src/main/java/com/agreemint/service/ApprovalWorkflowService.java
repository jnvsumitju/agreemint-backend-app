package com.agreemint.service;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.ApprovalStepRequest;
import com.agreemint.api.dto.ApprovalStepResponse;
import com.agreemint.api.dto.ApprovalWorkflowResponse;
import com.agreemint.api.dto.PendingApprovalResponse;
import com.agreemint.domain.ApprovalStatus;
import com.agreemint.domain.ApprovalStep;
import com.agreemint.domain.ApprovalWorkflow;
import com.agreemint.domain.DocumentLifecycleEvent;
import com.agreemint.domain.GeneratedDocument;
import com.agreemint.domain.LifecycleStatus;
import com.agreemint.domain.User;
import com.agreemint.repository.ApprovalStepRepository;
import com.agreemint.repository.ApprovalWorkflowRepository;
import com.agreemint.repository.DocumentLifecycleEventRepository;
import com.agreemint.repository.GeneratedDocumentRepository;
import com.agreemint.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ApprovalWorkflowService {

    private final ApprovalWorkflowRepository workflowRepo;
    private final ApprovalStepRepository stepRepo;
    private final GeneratedDocumentRepository documentRepo;
    private final DocumentLifecycleEventRepository eventRepo;
    private final UserRepository userRepo;
    private final NotificationService notificationService;
    private final ActivityService activityService;
    private final EmailService emailService;

    public ApprovalWorkflowService(
            ApprovalWorkflowRepository workflowRepo,
            ApprovalStepRepository stepRepo,
            GeneratedDocumentRepository documentRepo,
            DocumentLifecycleEventRepository eventRepo,
            UserRepository userRepo,
            NotificationService notificationService,
            ActivityService activityService,
            EmailService emailService) {
        this.workflowRepo = workflowRepo;
        this.stepRepo = stepRepo;
        this.documentRepo = documentRepo;
        this.eventRepo = eventRepo;
        this.userRepo = userRepo;
        this.notificationService = notificationService;
        this.activityService = activityService;
        this.emailService = emailService;
    }

    @Transactional
    public ApprovalWorkflowResponse createWorkflow(UUID documentId, UUID orgId, UUID createdBy,
                                                    List<ApprovalStepRequest> stepRequests) {
        GeneratedDocument doc = documentRepo.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));

        if (doc.getLifecycleStatus() != LifecycleStatus.DRAFT) {
            throw new BadRequestException("Document must be in DRAFT status to create an approval workflow");
        }

        // Check no existing active workflow
        workflowRepo.findByDocumentId(documentId).ifPresent(existing -> {
            if (existing.getStatus() == ApprovalStatus.PENDING) {
                throw new BadRequestException("Document already has a pending approval workflow");
            }
        });

        User creator = userRepo.findById(createdBy)
                .orElseThrow(() -> new NotFoundException("User not found"));

        ApprovalWorkflow workflow = new ApprovalWorkflow();
        workflow.setDocument(doc);
        workflow.setOrgId(orgId);
        workflow.setCreatedBy(createdBy);
        workflow.setStatus(ApprovalStatus.PENDING);
        workflowRepo.save(workflow);

        for (int i = 0; i < stepRequests.size(); i++) {
            ApprovalStepRequest sr = stepRequests.get(i);
            User assignee = userRepo.findById(sr.assigneeId())
                    .orElseThrow(() -> new NotFoundException("Assignee not found: " + sr.assigneeId()));

            ApprovalStep step = new ApprovalStep();
            step.setWorkflow(workflow);
            step.setStepOrder(i + 1);
            step.setAssignee(assignee);
            step.setRoleLabel(sr.roleLabel());
            step.setStatus(ApprovalStatus.PENDING);
            stepRepo.save(step);
        }

        // Transition document to PENDING_REVIEW
        LifecycleStatus fromStatus = doc.getLifecycleStatus();
        doc.setLifecycleStatus(LifecycleStatus.PENDING_REVIEW);
        doc.setUpdatedAt(Instant.now());
        documentRepo.save(doc);

        // Record lifecycle event
        DocumentLifecycleEvent event = new DocumentLifecycleEvent();
        event.setDocument(doc);
        event.setActorId(createdBy);
        event.setActorName(creator.getName());
        event.setFromStatus(fromStatus);
        event.setToStatus(LifecycleStatus.PENDING_REVIEW);
        event.setEventType("SUBMITTED_FOR_REVIEW");
        eventRepo.save(event);

        // Activity log
        if (doc.getOrgId() != null) {
            activityService.log(doc.getOrgId(), createdBy, creator.getName(),
                    "submitted_for_review", "DOCUMENT", doc.getId(),
                    doc.getTitle() != null ? doc.getTitle() : "Untitled document");
        }

        // Notify first step assignee
        ApprovalStepRequest firstStep = stepRequests.get(0);
        User firstAssignee = userRepo.findById(firstStep.assigneeId()).orElse(null);
        if (firstAssignee != null) {
            String docTitle = doc.getTitle() != null ? doc.getTitle() : "Untitled document";
            notificationService.notify(firstAssignee.getId(), "APPROVAL_REQUEST",
                    "Review requested: " + docTitle,
                    "You have been assigned to review this document",
                    "DOCUMENT", doc.getId());

            emailService.sendApprovalRequestEmail(firstAssignee.getEmail(),
                    docTitle, firstAssignee.getName(),
                    "/documents/" + doc.getId());
        }

        return getWorkflow(documentId);
    }

    @Transactional
    public ApprovalWorkflowResponse approveStep(UUID stepId, UUID actorId, String comment) {
        ApprovalStep step = stepRepo.findById(stepId)
                .orElseThrow(() -> new NotFoundException("Approval step not found"));

        if (!step.getAssignee().getId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the assigned reviewer can approve this step");
        }

        if (step.getStatus() != ApprovalStatus.PENDING) {
            throw new BadRequestException("Step has already been decided");
        }

        ApprovalWorkflow workflow = step.getWorkflow();
        GeneratedDocument doc = workflow.getDocument();
        User actor = step.getAssignee();

        step.setStatus(ApprovalStatus.APPROVED);
        step.setComment(comment);
        step.setDecidedAt(Instant.now());
        stepRepo.save(step);

        // Record lifecycle event
        DocumentLifecycleEvent event = new DocumentLifecycleEvent();
        event.setDocument(doc);
        event.setActorId(actorId);
        event.setActorName(actor.getName());
        event.setFromStatus(doc.getLifecycleStatus());
        event.setToStatus(doc.getLifecycleStatus()); // stays PENDING_REVIEW until all steps done
        event.setEventType("STEP_APPROVED");
        event.setComment(comment);
        eventRepo.save(event);

        // Check if there's a next pending step
        var nextStep = stepRepo.findFirstByWorkflowIdAndStatusOrderByStepOrderAsc(
                workflow.getId(), ApprovalStatus.PENDING);

        if (nextStep.isPresent()) {
            // Notify next assignee
            User nextAssignee = nextStep.get().getAssignee();
            String docTitle = doc.getTitle() != null ? doc.getTitle() : "Untitled document";
            notificationService.notify(nextAssignee.getId(), "APPROVAL_REQUEST",
                    "Review requested: " + docTitle,
                    "Previous step was approved. You are next to review.",
                    "DOCUMENT", doc.getId());

            emailService.sendApprovalRequestEmail(nextAssignee.getEmail(),
                    docTitle, nextAssignee.getName(),
                    "/documents/" + doc.getId());
        } else {
            // All steps approved — complete workflow and transition document
            workflow.setStatus(ApprovalStatus.APPROVED);
            workflow.setCompletedAt(Instant.now());
            workflowRepo.save(workflow);

            LifecycleStatus fromStatus = doc.getLifecycleStatus();
            doc.setLifecycleStatus(LifecycleStatus.APPROVED);
            doc.setUpdatedAt(Instant.now());
            documentRepo.save(doc);

            DocumentLifecycleEvent approvalEvent = new DocumentLifecycleEvent();
            approvalEvent.setDocument(doc);
            approvalEvent.setActorName("System");
            approvalEvent.setFromStatus(fromStatus);
            approvalEvent.setToStatus(LifecycleStatus.APPROVED);
            approvalEvent.setEventType("WORKFLOW_APPROVED");
            eventRepo.save(approvalEvent);

            // Notify document creator
            if (doc.getCreatedBy() != null) {
                String docTitle = doc.getTitle() != null ? doc.getTitle() : "Untitled document";
                notificationService.notify(doc.getCreatedBy(), "APPROVAL_APPROVED",
                        "Document approved: " + docTitle,
                        "All reviewers have approved the document",
                        "DOCUMENT", doc.getId());

                User creator = userRepo.findById(doc.getCreatedBy()).orElse(null);
                if (creator != null) {
                    emailService.sendApprovalDecisionEmail(creator.getEmail(),
                            docTitle, "Approved", actor.getName(), comment,
                            "/documents/" + doc.getId());
                }
            }

            if (doc.getOrgId() != null) {
                activityService.log(doc.getOrgId(), actorId, actor.getName(),
                        "approved", "DOCUMENT", doc.getId(),
                        doc.getTitle() != null ? doc.getTitle() : "Untitled document");
            }
        }

        return getWorkflow(doc.getId());
    }

    @Transactional
    public ApprovalWorkflowResponse rejectStep(UUID stepId, UUID actorId, String comment) {
        ApprovalStep step = stepRepo.findById(stepId)
                .orElseThrow(() -> new NotFoundException("Approval step not found"));

        if (!step.getAssignee().getId().equals(actorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the assigned reviewer can reject this step");
        }

        if (step.getStatus() != ApprovalStatus.PENDING) {
            throw new BadRequestException("Step has already been decided");
        }

        ApprovalWorkflow workflow = step.getWorkflow();
        GeneratedDocument doc = workflow.getDocument();
        User actor = step.getAssignee();

        step.setStatus(ApprovalStatus.REJECTED);
        step.setComment(comment);
        step.setDecidedAt(Instant.now());
        stepRepo.save(step);

        // Skip remaining pending steps
        List<ApprovalStep> remainingSteps = stepRepo
                .findByWorkflowIdOrderByStepOrderAsc(workflow.getId())
                .stream()
                .filter(s -> s.getStatus() == ApprovalStatus.PENDING)
                .toList();
        for (ApprovalStep remaining : remainingSteps) {
            remaining.setStatus(ApprovalStatus.SKIPPED);
            stepRepo.save(remaining);
        }

        // Reject workflow
        workflow.setStatus(ApprovalStatus.REJECTED);
        workflow.setCompletedAt(Instant.now());
        workflowRepo.save(workflow);

        // Transition document to REJECTED
        LifecycleStatus fromStatus = doc.getLifecycleStatus();
        doc.setLifecycleStatus(LifecycleStatus.REJECTED);
        doc.setUpdatedAt(Instant.now());
        documentRepo.save(doc);

        // Record lifecycle event
        DocumentLifecycleEvent event = new DocumentLifecycleEvent();
        event.setDocument(doc);
        event.setActorId(actorId);
        event.setActorName(actor.getName());
        event.setFromStatus(fromStatus);
        event.setToStatus(LifecycleStatus.REJECTED);
        event.setEventType("WORKFLOW_REJECTED");
        event.setComment(comment);
        eventRepo.save(event);

        // Notify document creator
        if (doc.getCreatedBy() != null) {
            String docTitle = doc.getTitle() != null ? doc.getTitle() : "Untitled document";
            notificationService.notify(doc.getCreatedBy(), "APPROVAL_REJECTED",
                    "Document rejected: " + docTitle,
                    comment != null ? comment : "The document was rejected by a reviewer",
                    "DOCUMENT", doc.getId());

            User creator = userRepo.findById(doc.getCreatedBy()).orElse(null);
            if (creator != null) {
                emailService.sendApprovalDecisionEmail(creator.getEmail(),
                        docTitle, "Rejected", actor.getName(), comment,
                        "/documents/" + doc.getId());
            }
        }

        if (doc.getOrgId() != null) {
            activityService.log(doc.getOrgId(), actorId, actor.getName(),
                    "rejected", "DOCUMENT", doc.getId(),
                    doc.getTitle() != null ? doc.getTitle() : "Untitled document");
        }

        return getWorkflow(doc.getId());
    }

    @Transactional(readOnly = true)
    public ApprovalWorkflowResponse getWorkflow(UUID documentId) {
        ApprovalWorkflow workflow = workflowRepo.findByDocumentId(documentId)
                .orElseThrow(() -> new NotFoundException("No approval workflow found for this document"));

        List<ApprovalStepResponse> steps = stepRepo
                .findByWorkflowIdOrderByStepOrderAsc(workflow.getId())
                .stream()
                .map(ApprovalStepResponse::from)
                .toList();

        return new ApprovalWorkflowResponse(
                workflow.getId(),
                workflow.getDocument().getId(),
                workflow.getStatus(),
                steps,
                workflow.getCreatedAt(),
                workflow.getCompletedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<PendingApprovalResponse> getPendingApprovals(UUID userId) {
        return stepRepo.findByAssigneeIdAndStatus(userId, ApprovalStatus.PENDING)
                .stream()
                .map(step -> {
                    GeneratedDocument doc = step.getWorkflow().getDocument();
                    return new PendingApprovalResponse(
                            step.getId(),
                            doc.getId(),
                            doc.getTitle() != null ? doc.getTitle() : "Untitled document",
                            step.getRoleLabel(),
                            step.getWorkflow().getCreatedAt()
                    );
                })
                .toList();
    }
}
