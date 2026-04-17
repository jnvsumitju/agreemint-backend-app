package com.agreemint.api.dto;

import java.util.List;
import java.util.UUID;

public record RequestReviewsRequest(List<UUID> reviewerIds, String message) {}
