package com.agreemint.repository;

import com.agreemint.domain.ReviewStatus;
import com.agreemint.domain.TemplateReview;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TemplateReviewRepository extends JpaRepository<TemplateReview, UUID> {

    List<TemplateReview> findByTemplateIdOrderByCreatedAtDesc(UUID templateId);

    List<TemplateReview> findByTemplateIdAndVersionIdOrderByCreatedAtDesc(UUID templateId, UUID versionId);

    Optional<TemplateReview> findByVersionIdAndReviewerId(UUID versionId, UUID reviewerId);

    List<TemplateReview> findByReviewerIdAndStatusOrderByCreatedAtDesc(
            UUID reviewerId, ReviewStatus status, Pageable pageable);

    /**
     * Used by the commit gate: any review on {@code versionId} currently marked
     * {@link ReviewStatus#CHANGES_REQUESTED} blocks the next commit on that template.
     */
    List<TemplateReview> findByVersionIdAndStatus(UUID versionId, ReviewStatus status);
}
