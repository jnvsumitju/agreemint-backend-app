package com.agreemint.service;

import com.agreemint.api.dto.TemplateReviewResponse;

import java.util.List;

/**
 * Thrown by {@code TemplateDraftService.commitDraft} when mandatory review
 * feedback on the current latest version blocks the commit.
 *
 * <p>Mapped to HTTP 409 with a structured body containing the blocking reviews
 * so the frontend can render a dismiss / reopen UI (see ApiExceptionHandler).
 */
public class ReviewBlockException extends RuntimeException {

    private final List<TemplateReviewResponse> blockers;

    public ReviewBlockException(String message, List<TemplateReviewResponse> blockers) {
        super(message);
        this.blockers = blockers == null ? List.of() : blockers;
    }

    public List<TemplateReviewResponse> blockers() {
        return blockers;
    }
}
