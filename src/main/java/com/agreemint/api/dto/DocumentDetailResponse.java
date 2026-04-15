package com.agreemint.api.dto;

import java.util.List;

public record DocumentDetailResponse(
        DocumentLifecycleResponse document,
        List<DocumentTimelineEventResponse> timeline,
        ApprovalWorkflowResponse workflow
) {
}
