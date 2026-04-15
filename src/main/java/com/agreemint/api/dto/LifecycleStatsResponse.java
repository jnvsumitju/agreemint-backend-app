package com.agreemint.api.dto;

import com.agreemint.domain.LifecycleStatus;

import java.util.Map;

public record LifecycleStatsResponse(
        Map<LifecycleStatus, Long> counts,
        long total
) {
}
