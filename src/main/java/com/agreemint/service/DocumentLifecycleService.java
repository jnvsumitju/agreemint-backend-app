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
import com.agreemint.config.FrontendProperties;

import java.time.Duration;
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
    private final com.agreemint.repository.DocumentReceiptRepository receiptRepo;
    private final ApprovalWorkflowRepository workflowRepo;
    private final ApprovalStepRepository stepRepo;
    private final UserRepository userRepo;
    private final ProductRepository productRepo;
    private final NotificationService notificationService;
    private final ActivityService activityService;
    private final EmailService emailService;
    private final FrontendProperties frontend;
    private final WebhookService webhookService;

    public DocumentLifecycleService(
            GeneratedDocumentRepository documentRepo,
            DocumentLifecycleEventRepository eventRepo,
            com.agreemint.repository.DocumentReceiptRepository receiptRepo,
            ApprovalWorkflowRepository workflowRepo,
            ApprovalStepRepository stepRepo,
            UserRepository userRepo,
            ProductRepository productRepo,
            NotificationService notificationService,
            ActivityService activityService,
            EmailService emailService,
            FrontendProperties frontend,
            WebhookService webhookService) {
        this.documentRepo = documentRepo;
        this.eventRepo = eventRepo;
        this.receiptRepo = receiptRepo;
        this.workflowRepo = workflowRepo;
        this.stepRepo = stepRepo;
        this.userRepo = userRepo;
        this.productRepo = productRepo;
        this.notificationService = notificationService;
        this.activityService = activityService;
        this.emailService = emailService;
        this.frontend = frontend;
        this.webhookService = webhookService;
    }

    /**
     * Absolute link to a document, for emails.
     *
     * <p>The lifecycle emails used to pass a bare {@code "/documents/{id}"},
     * which Thymeleaf rendered straight into an {@code href} — a dead link in
     * every mail client, since there is no origin to resolve it against.
     */
    private String documentLink(UUID documentId) {
        return frontend.getBaseUrl() + "/documents/" + documentId;
    }


    /**
     * Load a document and prove it belongs to the workspace the caller is acting in.
     *
     * <p>Role and plan are checked in the controller against
     * {@code principal.orgId()} — the caller's OWN workspace. That establishes
     * "you are an admin somewhere", not "you may touch this document". Without
     * this second check an admin of any Pro workspace could pass a document id
     * belonging to a different customer and mutate it: the repository lookup is
     * by primary key and carries no tenant predicate.
     *
     * <p>404 rather than 403 on mismatch, so the endpoint does not confirm that
     * an id exists in someone else's workspace.
     */
    private GeneratedDocument loadInOrg(UUID documentId, UUID actingOrgId) {
        GeneratedDocument doc = documentRepo.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        if (actingOrgId == null || !actingOrgId.equals(doc.getOrgId())) {
            throw new NotFoundException("Document not found");
        }
        return doc;
    }

    @Transactional
    public DocumentLifecycleResponse transitionStatus(UUID documentId, LifecycleStatus targetStatus,
                                                       UUID actorId, String comment, UUID actingOrgId) {
        GeneratedDocument doc = loadInOrg(documentId, actingOrgId);

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
                        documentLink(doc.getId()));
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
    public DocumentDetailResponse getDocumentWithTimeline(UUID documentId, UUID actingOrgId) {
        GeneratedDocument doc = loadInOrg(documentId, actingOrgId);

        List<DocumentTimelineEventResponse> timeline = eventRepo
                .findByDocumentIdOrderByCreatedAtDesc(documentId)
                .stream()
                .map(DocumentTimelineEventResponse::from)
                .toList();

        ApprovalWorkflowResponse workflow = workflowRepo.findByDocumentId(documentId)
                .map(this::buildWorkflowResponse)
                .orElse(null);

        // Null for anything generated before V26, which is correct rather than
        // unfortunate: we cannot claim a digest for bytes we never fingerprinted.
        String sha256 = receiptRepo.findFirstByDocumentIdOrderByIssuedAtAsc(documentId)
                .map(com.agreemint.domain.DocumentReceipt::getSha256)
                .orElse(null);

        return new DocumentDetailResponse(
                singleResponse(doc),
                timeline,
                workflow,
                sha256
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

    /**
     * Set or clear a document's expiration date.
     *
     * <p>This is the write path the whole expiry feature was missing.
     * {@code expires_at} has existed since V11 along with an hourly sweep that
     * expires past-due documents, but nothing ever assigned it — the column was
     * always NULL, so the sweep matched nothing and the advertised behaviour had
     * never once run.
     *
     * @param expiresAt the new date, or null to remove the expiry entirely
     */
    @Transactional
    public DocumentLifecycleResponse setExpiry(UUID documentId, Instant expiresAt, UUID actorId,
                                                UUID actingOrgId) {
        GeneratedDocument doc = loadInOrg(documentId, actingOrgId);

        // Same rule as transitionStatus: an API-generated document does not
        // participate in the lifecycle, so accepting a date that can only be
        // enforced by a lifecycle transition would be a promise we refuse to keep.
        if (doc.getSource() == DocumentSource.API_GENERATED) {
            throw new BadRequestException(
                    "API-generated documents do not participate in the lifecycle workflow");
        }
        if (expiresAt != null && expiresAt.isBefore(Instant.now())) {
            throw new BadRequestException("Expiration date must be in the future");
        }

        User actor = userRepo.findById(actorId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        Instant previous = doc.getExpiresAt();
        doc.setExpiresAt(expiresAt);
        // Re-arm the warning. Without this, moving a date further out leaves the
        // old "already warned" marker set and the customer is never told about
        // the new date.
        doc.setExpiryWarnedAt(null);
        doc.setUpdatedAt(Instant.now());
        documentRepo.save(doc);

        DocumentLifecycleEvent event = new DocumentLifecycleEvent();
        event.setDocument(doc);
        event.setActorId(actorId);
        event.setActorName(actor.getName());
        event.setFromStatus(doc.getLifecycleStatus());
        event.setToStatus(doc.getLifecycleStatus());
        event.setEventType(expiresAt == null ? "EXPIRY_CLEARED" : "EXPIRY_SET");
        event.setComment(expiresAt == null
                ? (previous == null ? "No expiration date" : "Expiration removed")
                : "Expires " + expiresAt);
        eventRepo.save(event);

        if (doc.getOrgId() != null) {
            activityService.log(doc.getOrgId(), actorId, actor.getName(),
                    expiresAt == null ? "expiry.cleared" : "expiry.set", "DOCUMENT", doc.getId(),
                    doc.getTitle() != null ? doc.getTitle() : "Untitled document");
        }

        return singleResponse(doc);
    }

    /**
     * Warn about documents due to expire inside the lookahead window.
     *
     * <p>Split from {@link #expireDocuments()} on purpose: that one fires
     * *after* the date and tells someone their document is already gone, which
     * is exactly the notice that arrives too late to act on.
     *
     * @param leadDays how far ahead to look
     * @param batchSize cap on documents handled per run
     * @return how many warnings were sent
     */
    @Transactional
    public int sendExpiryWarnings(int leadDays, int batchSize) {
        Instant now = Instant.now();
        Instant horizon = now.plus(Duration.ofDays(leadDays));

        List<GeneratedDocument> due = documentRepo
                .findByLifecycleStatusAndSourceNotAndExpiryWarnedAtIsNullAndExpiresAtBetweenOrderByExpiresAtAsc(
                        LifecycleStatus.ACTIVE, DocumentSource.API_GENERATED,
                        now, horizon, PageRequest.of(0, batchSize));

        int sent = 0;
        for (GeneratedDocument doc : due) {
            // Stamp first. If the email throws, the customer misses one warning;
            // if we stamped last, a failure part-way through a multi-instance
            // run would send the same warning again on the next sweep.
            doc.setExpiryWarnedAt(now);
            documentRepo.save(doc);

            String title = doc.getTitle() != null ? doc.getTitle() : "Untitled document";
            if (doc.getCreatedBy() != null) {
                notificationService.notify(doc.getCreatedBy(), "DOCUMENT_EXPIRING",
                        "Expiring soon: " + title, null, "DOCUMENT", doc.getId());

                User creator = userRepo.findById(doc.getCreatedBy()).orElse(null);
                if (creator != null) {
                    emailService.sendDocumentExpiringSoonEmail(creator.getEmail(), title,
                            String.valueOf(doc.getExpiresAt()), documentLink(doc.getId()));
                }
            }
            webhookService.emit(doc.getOrgId(), "document.expiring", Map.of(
                    "documentId", doc.getId().toString(),
                    "title", title,
                    "expiresAt", String.valueOf(doc.getExpiresAt())));
            sent++;
        }
        if (sent > 0) log.info("Sent {} document expiry warnings", sent);
        return sent;
    }

    /** Scheduled job: auto-expire ACTIVE documents past their expiration date. */
    @Scheduled(fixedDelay = 3600000) // every hour
    @Transactional
    public void expireDocuments() {
        List<GeneratedDocument> expired = documentRepo.findByExpiresAtBeforeAndLifecycleStatus(
                Instant.now(), LifecycleStatus.ACTIVE);

        for (GeneratedDocument doc : expired) {
            // transitionStatus refuses lifecycle moves for API-generated
            // documents, and this sweep sets the status directly — without the
            // same guard it would push them into EXPIRED anyway, contradicting
            // the contract the manual path enforces.
            if (doc.getSource() == DocumentSource.API_GENERATED) continue;

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
                            documentLink(doc.getId()));
                }
            }

            webhookService.emit(doc.getOrgId(), "document.expired", Map.of(
                    "documentId", doc.getId().toString(),
                    "title", doc.getTitle() != null ? doc.getTitle() : "Untitled document",
                    "expiresAt", String.valueOf(doc.getExpiresAt())));

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
