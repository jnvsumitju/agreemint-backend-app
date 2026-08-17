package com.agreemint.api.dto;

import java.util.List;

/**
 * @param sha256 Digest of the issued PDF, or null for documents generated
 *               before receipts existed (and for any that failed to render).
 *               Shown so the issuer can hand it to a recipient out of band —
 *               a recipient who has both the file and an independently-received
 *               digest does not need to trust our verification page either.
 */
public record DocumentDetailResponse(
        DocumentLifecycleResponse document,
        List<DocumentTimelineEventResponse> timeline,
        ApprovalWorkflowResponse workflow,
        String sha256
) {
}
