package com.agreemint.api.dto;

import com.agreemint.domain.ReviewStatus;

public record DecideReviewRequest(ReviewStatus status, String summary) {}
