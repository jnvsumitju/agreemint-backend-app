package com.agreemint.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public enum LifecycleStatus {
    DRAFT,
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    SENT,
    SIGNED,
    ACTIVE,
    EXPIRED,
    ARCHIVED;

    private static final Map<LifecycleStatus, Set<LifecycleStatus>> TRANSITIONS = new EnumMap<>(LifecycleStatus.class);

    static {
        TRANSITIONS.put(DRAFT, Set.of(PENDING_REVIEW, ARCHIVED));
        TRANSITIONS.put(PENDING_REVIEW, Set.of(APPROVED, REJECTED));
        TRANSITIONS.put(REJECTED, Set.of(DRAFT, ARCHIVED));
        TRANSITIONS.put(APPROVED, Set.of(SENT, ACTIVE, ARCHIVED));
        TRANSITIONS.put(SENT, Set.of(SIGNED, ARCHIVED));
        TRANSITIONS.put(SIGNED, Set.of(ACTIVE, ARCHIVED));
        TRANSITIONS.put(ACTIVE, Set.of(EXPIRED, ARCHIVED));
        TRANSITIONS.put(EXPIRED, Set.of(ARCHIVED));
        TRANSITIONS.put(ARCHIVED, Set.of());
    }

    public static Set<LifecycleStatus> allowedTransitions(LifecycleStatus from) {
        return TRANSITIONS.getOrDefault(from, Set.of());
    }

    public boolean canTransitionTo(LifecycleStatus target) {
        return allowedTransitions(this).contains(target);
    }
}
