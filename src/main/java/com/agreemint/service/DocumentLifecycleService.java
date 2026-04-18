package com.agreemint.service;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.ApprovalStepResponse;
import com.agreemint.api.dto.ApprovalWorkflowResponse;
import com.agreemint.api.dto.DocumentDetailResponse;
import com.agreemint.api.dto.DocumentLifecycleResponse;
import com.agreemint.api.dto.DocumentTimelineEventResponse;
import com.agreemint.api.dto.LifecycleStatsResponse;
import com.agreemint.domain.ApprovalWorkflow;
import com.agreemint.domain.DocumentLifecycleEvent;
import com.agreemint.domain.DocumentSource;
import com.agreemint.domain.GeneratedDocument;
import com.agreemint.domain.LifecycleStatus;
import com.agreemint.domain.Product;
import com.agreemint.domain.User;
import com.agreemint.repository.ApprovalStepRepository;
import com.agreemint.repository.ApprovalWorkflowRepository;
import com.agreemint.repository.DocumentLifecycleEventRepository;
import com.agreemint.repository.GeneratedDocumentRepository;
import com.agreemint.repository.ProductRepository;
import com.agreemint.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class DocumentLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(DocumentLifecycleService.class);

    private final GeneratedDocumentRepository documentRepo;
    private final DocumentLifecycleEventRepository eventRepo;
    private final ApprovalWorkflowRepository workflowRepo;
    private final ApprovalStepRepository stepRepo;
    private final UserRepository userRepo;
    private final ProductRepository productRepo;
    private final NotificationService notificationService;
    private final ActivityService activityService;
    private final EmailService emailService;

    public DocumentLifecycleService(
            GeneratedDocumentRepository documentRepo,
            DocumentLifecycleEventRepository eventRepo,
            ApprovalWorkflowRepository workflowRepo,
            ApprovalStepRepository stepRepo,
            UserRepository userRepo,
            ProductRepository productRepo,
            NotificationService notificationService,
            ActivityService activityService,
            EmailService emailService) {
        this.documentRepo = documentRepo;
        this.eventRepo = eventRepo;
        this.workflowRepo = workflowRepo;
        this.stepRepo = stepRepo;
        this.userRepo = userRepo;
        this.productRepo = productRepo;
        this.notificationService = notificationService;
        this.activityService = activityService;
        this.emailService = emailService;
    }

    @Transactional
    public DocumentLifecycleResponse transitionStatus(UUID documentId, LifecycleStatus targetStatus,
                                                       UUID actorId, String comment) {
        GeneratedDocument doc = documentRepo.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));

        // API-sourced documents skip the lifecycle entirely — customers run
        // their own review/approval on their side. Reject transition attempts
        // loudly so a misconfigured UI can't push a terminal state on them.
        if (doc.getSource() == DocumentSource.API_GENERATED) {
            throw new BadRequestException(
                    "API-generated documents do not participate in the lifecycle workflow");
        }

        LifecycleStatus currentStatus = doc.getLifecycleStatus();
        if (currentStatus == null) currentStatus = LifecycleStatus.DRAFT;

        if (!currentStatus.canTransitionTo(targetStatus)) {
            throw new BadRequestException(
                    "Cannot transition from " + currentStatus + " to " + targetStatus);
        }

        User actor = userRepo.findById(actorId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        LifecycleStatus fromStatus = doc.getLifecycleStatus();
        doc.setLifecycleStatus(targetStatus);
        doc.setUpdatedAt(Instant.now());
        documentRepo.save(doc);

        // Record timeline event
        DocumentLifecycleEvent event = new DocumentLifecycleEvent();
        event.setDocument(doc);
        event.setActorId(actorId);
        event.setActorName(actor.getName());
        event.setFromStatus(fromStatus);
        event.setToStatus(targetStatus);
        event.setEventType("STATUS_CHANGE");
        event.setComment(comment);
        eventRepo.save(event);

        // Activity log
        if (doc.getOrgId() != null) {
            activityService.log(doc.getOrgId(), actorId, actor.getName(),
                    targetStatus.name().toLowerCase(), "DOCUMENT", doc.getId(),
                    doc.getTitle() != null ? doc.getTitle() : "Untitled document");
        }

        // Notify document creator (if different from actor)
        if (doc.getCreatedBy() != null && !doc.getCreatedBy().equals(actorId)) {
            String title = "Document status changed to " + targetStatus.name().replace('_', ' ');
            notificationService.notify(doc.getCreatedBy(), "DOCUMENT_STATUS_CHANGED",
                    title, comment, "DOCUMENT", doc.getId());

            User creator = userRepo.findById(doc.getCreatedBy()).orElse(null);
            if (creator != null) {
                emailService.sendLifecycleChangeEmail(creator.getEmail(),
                        doc.getTitle() != null ? doc.getTitle() : "Untitled document",
                        targetStatus.name().replace('_', ' '),
                        actor.getName(),
                        "/documents/" + doc.getId());
            }
        }

        return singleResponse(doc);
    }

    @Transactional(readOnly = true)
    public List<DocumentLifecycleResponse> listDocuments(UUID orgId, LifecycleStatus filterStatus, int page, int size) {
        return listDocuments(orgId, null, filterStatus, page, size);
    }

    /** Single-row response with the product name looked up on demand. */
    private DocumentLifecycleResponse singleResponse(GeneratedDocument doc) {
        UUID productId = doc.getTemplate().getProductId();
        String productName = productId == null
                ? null
                : productRepo.findById(productId).map(Product::getName).orElse(null);
        return DocumentLifecycleResponse.from(doc, productName);
    }

    /**
     * List documents with optional source + lifecycle-status filters. The
     * Documents page uses {@code source} to populate its "UI / API" tabs and
     * layers {@code filterStatus} on top for the UI tab only (API docs have
     * no lifecycle).
     */
    @Transactional(readOnly = true)
    public List<DocumentLifecycleResponse> listDocuments(UUID orgId, DocumentSource source,
                                                          LifecycleStatus filterStatus,
                                                          int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        List<GeneratedDocument> docs;
        if (source != null && filterStatus != null) {
            docs = documentRepo.findByOrgIdAndSourceAndLifecycleStatusOrderByCreatedAtDesc(
                    orgId, source, filterStatus, pageable);
        } else if (source != null) {
            docs = documentRepo.findByOrgIdAndSourceOrderByCreatedAtDesc(orgId, source, pageable);
        } else if (filterStatus != null) {
            docs = documentRepo.findByOrgIdAndLifecycleStatusOrderByCreatedAtDesc(orgId, filterStatus, pageable);
        } else {
            docs = documentRepo.findByOrgIdOrderByCreatedAtDesc(orgId, pageable);
        }
        Map<UUID, String> productNames = resolveProductNames(docs);
        return docs.stream()
                .map(d -> DocumentLifecycleResponse.from(d,
                        productNames.get(d.getTemplate().getProductId())))
                .toList();
    }

    /** Batch the product-name lookup for a page of documents so rendering
     *  the Documents table doesn't N+1 the products table. */
    private Map<UUID, String> resolveProductNames(List<GeneratedDocument> docs) {
        List<UUID> productIds = docs.stream()
                .map(d -> d.getTemplate().getProductId())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (productIds.isEmpty()) return Map.of();
        Map<UUID, String> out = new HashMap<>();
        for (Product p : productRepo.findAllById(productIds)) {
            out.put(p.getId(), p.getName());
        }
        return out;
    }

    @Transactional(readOnly = true)
    public DocumentDetailResponse getDocumentWithTimeline(UUID documentId) {
        GeneratedDocument doc = documentRepo.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));

        List<DocumentTimelineEventResponse> timeline = eventRepo
                .findByDocumentIdOrderByCreatedAtDesc(documentId)
                .stream()
                .map(DocumentTimelineEventResponse::from)
                .toList();

        ApprovalWorkflowResponse workflow = workflowRepo.findByDocumentId(documentId)
                .map(this::buildWorkflowResponse)
                .orElse(null);

        return new DocumentDetailResponse(
                singleResponse(doc),
                timeline,
                workflow
        );
    }

    @Transactional(readOnly = true)
    public LifecycleStatsResponse getLifecycleStats(UUID orgId) {
        Map<LifecycleStatus, Long> counts = new EnumMap<>(LifecycleStatus.class);
        long total = 0;
        for (LifecycleStatus status : LifecycleStatus.values()) {
            long count = documentRepo.countByOrgIdAndLifecycleStatus(orgId, status);
            counts.put(status, count);
            total += count;
        }
        return new LifecycleStatsResponse(counts, total);
    }

    /** Scheduled job: auto-expire ACTIVE documents past their expiration date. */
    @Scheduled(fixedDelay = 3600000) // every hour
    @Transactional
    public void expireDocuments() {
        List<GeneratedDocument> expired = documentRepo.findByExpiresAtBeforeAndLifecycleStatus(
                Instant.now(), LifecycleStatus.ACTIVE);

        for (GeneratedDocument doc : expired) {
            LifecycleStatus from = doc.getLifecycleStatus();
            doc.setLifecycleStatus(LifecycleStatus.EXPIRED);
            doc.setUpdatedAt(Instant.now());
            documentRepo.save(doc);

            DocumentLifecycleEvent event = new DocumentLifecycleEvent();
            event.setDocument(doc);
            event.setActorName("System");
            event.setFromStatus(from);
            event.setToStatus(LifecycleStatus.EXPIRED);
            event.setEventType("AUTO_EXPIRED");
            eventRepo.save(event);

            if (doc.getCreatedBy() != null) {
                notificationService.notify(doc.getCreatedBy(), "DOCUMENT_EXPIRING",
                        "Document expired: " + (doc.getTitle() != null ? doc.getTitle() : "Untitled"),
                        null, "DOCUMENT", doc.getId());

                User creator = userRepo.findById(doc.getCreatedBy()).orElse(null);
                if (creator != null) {
                    emailService.sendExpirationWarningEmail(creator.getEmail(),
                            doc.getTitle() != null ? doc.getTitle() : "Untitled document",
                            doc.getExpiresAt() != null ? doc.getExpiresAt().toString() : "now",
                            "/documents/" + doc.getId());
                }
            }

            log.info("Auto-expired document {}", doc.getId());
        }
    }

    private ApprovalWorkflowResponse buildWorkflowResponse(ApprovalWorkflow workflow) {
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
}
