package com.agreemint.service;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.TemplateReviewResponse;
import com.agreemint.api.dto.TemplateReviewResponse.ReviewerInfo;
import com.agreemint.config.FrontendProperties;
import com.agreemint.domain.ReviewStatus;
import com.agreemint.domain.Template;
import com.agreemint.domain.TemplateReview;
import com.agreemint.domain.TemplateVersion;
import com.agreemint.domain.User;
import com.agreemint.repository.TemplateRepository;
import com.agreemint.repository.TemplateReviewRepository;
import com.agreemint.repository.TemplateVersionRepository;
import com.agreemint.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business logic for the template review workflow (JIRA/GitHub-PR style).
 *
 * <p>Lifecycle per {@link TemplateReview} row:
 * <pre>
 *   requestReviews (or reopen)    → PENDING
 *   reviewer decide APPROVED      → APPROVED
 *   reviewer decide CHANGES_REQ   → CHANGES_REQUESTED   (blocks the next commit)
 *   requester dismiss             → DISMISSED
 * </pre>
 *
 * <p>The commit gate is implemented by {@link #blockingReviewsForLatestVersion(UUID)};
 * it is consulted from {@code TemplateDraftService.commitDraft}.
 */
@Service
public class TemplateReviewService {

    private final TemplateReviewRepository reviewRepo;
    private final TemplateVersionRepository versionRepo;
    private final TemplateRepository templateRepo;
    private final UserRepository userRepo;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final FrontendProperties frontendProps;

    public TemplateReviewService(
            TemplateReviewRepository reviewRepo,
            TemplateVersionRepository versionRepo,
            TemplateRepository templateRepo,
            UserRepository userRepo,
            NotificationService notificationService,
            EmailService emailService,
            FrontendProperties frontendProps) {
        this.reviewRepo = reviewRepo;
        this.versionRepo = versionRepo;
        this.templateRepo = templateRepo;
        this.userRepo = userRepo;
        this.notificationService = notificationService;
        this.emailService = emailService;
        this.frontendProps = frontendProps;
    }

    // ── Write ────────────────────────────────────────────────────────────────

    /**
     * Create (or re-arm) review rows for each reviewer id. Dedupes on
     * (version_id, reviewer_id): if one already exists, its status is flipped
     * back to PENDING. Notifies each reviewer by email + in-app.
     *
     * @return the list of affected reviews, denormalised for the API.
     */
    @Transactional
    public List<TemplateReviewResponse> requestReviews(
            UUID templateId, UUID versionId, UUID requesterId,
            List<UUID> reviewerIds, String message) {

        if (reviewerIds == null || reviewerIds.isEmpty()) {
            throw new BadRequestException("reviewerIds is required");
        }

        Template template = templateRepo.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Template not found"));
        TemplateVersion version = versionRepo.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Version not found"));
        if (!version.getTemplate().getId().equals(templateId)) {
            throw new BadRequestException("Version does not belong to template");
        }
        User requester = userRepo.findById(requesterId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Requester not found"));

        List<TemplateReview> affected = new ArrayList<>();
        for (UUID reviewerId : reviewerIds) {
            if (reviewerId == null) continue;
            if (reviewerId.equals(requesterId)) {
                // Self-review is allowed — sometimes you want to flag "I want to re-check"
                // against yourself — but we still create the row so UX is predictable.
            }
            User reviewer = userRepo.findById(reviewerId).orElse(null);
            if (reviewer == null) continue;

            TemplateReview review = reviewRepo.findByVersionIdAndReviewerId(versionId, reviewerId)
                    .orElseGet(() -> {
                        TemplateReview fresh = new TemplateReview();
                        fresh.setTemplateId(templateId);
                        fresh.setVersionId(versionId);
                        fresh.setReviewerId(reviewerId);
                        return fresh;
                    });
            review.setRequesterId(requesterId);
            review.setStatus(ReviewStatus.PENDING);
            review.setMessage(message);
            review.setSummary(null);
            review.setDecidedAt(null);
            if (review.getCreatedAt() == null) review.setCreatedAt(Instant.now());
            reviewRepo.save(review);
            affected.add(review);

            // Notify reviewer
            String title = requester.getName() + " requested your review";
            String body = "on \"" + template.getName() + "\" (v" + version.getVersionNumber() + ")";
            notificationService.notify(
                    reviewerId, "REVIEW_REQUEST", title, body, "TEMPLATE", templateId);
            emailService.sendReviewRequestedEmail(
                    reviewer.getEmail(),
                    template.getName(),
                    requester.getName(),
                    version.getVersionNumber(),
                    message,
                    templateEditorUrl(templateId));
        }

        return toResponses(affected);
    }

    /**
     * Reviewer submits a decision (APPROVED or CHANGES_REQUESTED) with an optional summary.
     * Notifies the original requester.
     */
    @Transactional
    public TemplateReviewResponse decide(UUID reviewId, UUID reviewerUserId, ReviewStatus decision, String summary) {
        if (decision != ReviewStatus.APPROVED && decision != ReviewStatus.CHANGES_REQUESTED) {
            throw new BadRequestException("status must be APPROVED or CHANGES_REQUESTED");
        }
        TemplateReview review = reviewRepo.findById(reviewId)
                .orElseThrow(() -> new NotFoundException("Review not found"));
        if (!review.getReviewerId().equals(reviewerUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the assigned reviewer can decide");
        }

        review.setStatus(decision);
        review.setSummary(summary);
        review.setDecidedAt(Instant.now());
        reviewRepo.save(review);

        notifyRequesterOfDecision(review, decision, summary);
        return toResponse(review);
    }

    /** Requester (or ADMIN, checked at the controller) removes a review from the blocking set. */
    @Transactional
    public TemplateReviewResponse dismiss(UUID reviewId) {
        TemplateReview review = reviewRepo.findById(reviewId)
                .orElseThrow(() -> new NotFoundException("Review not found"));
        review.setStatus(ReviewStatus.DISMISSED);
        review.setDecidedAt(Instant.now());
        reviewRepo.save(review);

        // Let the reviewer know their review was set aside.
        Template template = templateRepo.findById(review.getTemplateId()).orElse(null);
        if (template != null) {
            notificationService.notify(
                    review.getReviewerId(),
                    "REVIEW_DISMISSED",
                    "Your review was dismissed",
                    "\"" + template.getName() + "\"",
                    "TEMPLATE",
                    review.getTemplateId());
        }
        return toResponse(review);
    }

    /** Requester re-asks the same reviewer to re-evaluate (after addressing changes). */
    @Transactional
    public TemplateReviewResponse reopen(UUID reviewId) {
        TemplateReview review = reviewRepo.findById(reviewId)
                .orElseThrow(() -> new NotFoundException("Review not found"));
        review.setStatus(ReviewStatus.PENDING);
        review.setSummary(null);
        review.setDecidedAt(null);
        reviewRepo.save(review);

        Template template = templateRepo.findById(review.getTemplateId()).orElse(null);
        if (template != null) {
            notificationService.notify(
                    review.getReviewerId(),
                    "REVIEW_REOPENED",
                    "Review re-requested",
                    "\"" + template.getName() + "\" needs another look",
                    "TEMPLATE",
                    review.getTemplateId());
            User reviewer = userRepo.findById(review.getReviewerId()).orElse(null);
            User requester = userRepo.findById(review.getRequesterId()).orElse(null);
            if (reviewer != null && requester != null) {
                TemplateVersion version = versionRepo.findById(review.getVersionId()).orElse(null);
                emailService.sendReviewRequestedEmail(
                        reviewer.getEmail(),
                        template.getName(),
                        requester.getName(),
                        version != null ? version.getVersionNumber() : 0,
                        null,
                        templateEditorUrl(review.getTemplateId()));
            }
        }
        return toResponse(review);
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<TemplateReviewResponse> list(UUID templateId) {
        return toResponses(reviewRepo.findByTemplateIdOrderByCreatedAtDesc(templateId));
    }

    @Transactional(readOnly = true)
    public List<TemplateReviewResponse> listForVersion(UUID templateId, UUID versionId) {
        return toResponses(reviewRepo.findByTemplateIdAndVersionIdOrderByCreatedAtDesc(templateId, versionId));
    }

    @Transactional(readOnly = true)
    public List<TemplateReviewResponse> listAssignedToMe(UUID userId, int limit) {
        return toResponses(reviewRepo.findByReviewerIdAndStatusOrderByCreatedAtDesc(
                userId, ReviewStatus.PENDING, PageRequest.of(0, limit)));
    }

    /**
     * Returns the reviews on the latest committed version that are in
     * {@link ReviewStatus#CHANGES_REQUESTED} and therefore block the next commit.
     * Empty list means the commit may proceed.
     */
    @Transactional(readOnly = true)
    public List<TemplateReviewResponse> blockingReviewsForLatestVersion(UUID templateId) {
        Template template = templateRepo.findById(templateId).orElse(null);
        if (template == null) return List.of();
        TemplateVersion latest = versionRepo.findFirstByTemplateOrderByVersionNumberDesc(template).orElse(null);
        if (latest == null) return List.of();
        return toResponses(
                reviewRepo.findByVersionIdAndStatus(latest.getId(), ReviewStatus.CHANGES_REQUESTED));
    }

    @Transactional(readOnly = true)
    public boolean isRequester(UUID reviewId, UUID userId) {
        return reviewRepo.findById(reviewId)
                .map(r -> r.getRequesterId().equals(userId))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public UUID templateIdOf(UUID reviewId) {
        return reviewRepo.findById(reviewId).map(TemplateReview::getTemplateId).orElse(null);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void notifyRequesterOfDecision(TemplateReview review, ReviewStatus decision, String summary) {
        Template template = templateRepo.findById(review.getTemplateId()).orElse(null);
        User reviewer = userRepo.findById(review.getReviewerId()).orElse(null);
        User requester = userRepo.findById(review.getRequesterId()).orElse(null);
        if (template == null || reviewer == null || requester == null) return;

        String type = decision == ReviewStatus.APPROVED ? "REVIEW_APPROVED" : "REVIEW_CHANGES_REQUESTED";
        String verb = decision == ReviewStatus.APPROVED ? "approved" : "requested changes on";
        String title = reviewer.getName() + " " + verb + " your version";
        String body = "\"" + template.getName() + "\"";
        notificationService.notify(
                requester.getId(), type, title, body, "TEMPLATE", review.getTemplateId());

        emailService.sendReviewDecisionEmail(
                requester.getEmail(),
                template.getName(),
                reviewer.getName(),
                decision.name(),
                summary,
                templateEditorUrl(review.getTemplateId()));
    }

    private String templateEditorUrl(UUID templateId) {
        String base = frontendProps.getBaseUrl();
        if (base == null || base.isEmpty()) base = "";
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/templates/" + templateId;
    }

    private TemplateReviewResponse toResponse(TemplateReview r) {
        return toResponses(List.of(r)).get(0);
    }

    /**
     * Bulk conversion that batches the user + version lookups to avoid N+1s when
     * rendering the Reviews panel.
     */
    private List<TemplateReviewResponse> toResponses(List<TemplateReview> reviews) {
        if (reviews.isEmpty()) return List.of();
        Map<UUID, User> users = new HashMap<>();
        Map<UUID, TemplateVersion> versions = new HashMap<>();
        for (TemplateReview r : reviews) {
            users.computeIfAbsent(r.getRequesterId(), id -> userRepo.findById(id).orElse(null));
            users.computeIfAbsent(r.getReviewerId(), id -> userRepo.findById(id).orElse(null));
            versions.computeIfAbsent(r.getVersionId(), id -> versionRepo.findById(id).orElse(null));
        }
        List<TemplateReviewResponse> out = new ArrayList<>(reviews.size());
        for (TemplateReview r : reviews) {
            User requester = users.get(r.getRequesterId());
            User reviewer = users.get(r.getReviewerId());
            TemplateVersion version = versions.get(r.getVersionId());
            out.add(new TemplateReviewResponse(
                    r.getId(),
                    r.getTemplateId(),
                    r.getVersionId(),
                    version != null ? version.getVersionNumber() : 0,
                    toReviewerInfo(requester),
                    toReviewerInfo(reviewer),
                    r.getStatus(),
                    r.getMessage(),
                    r.getSummary(),
                    r.getCreatedAt(),
                    r.getDecidedAt()
            ));
        }
        return out;
    }

    private ReviewerInfo toReviewerInfo(User u) {
        if (u == null) return new ReviewerInfo(null, "(unknown)", "", null);
        return new ReviewerInfo(u.getId(), u.getName(), u.getEmail(), u.getAvatarUrl());
    }
}
