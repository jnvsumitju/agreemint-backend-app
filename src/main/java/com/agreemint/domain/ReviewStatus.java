package com.agreemint.domain;

/**
 * Lifecycle states of a {@link TemplateReview}.
 *
 * <ul>
 *     <li>{@link #PENDING}         — awaiting the reviewer's decision.</li>
 *     <li>{@link #APPROVED}        — reviewer approved the version.</li>
 *     <li>{@link #CHANGES_REQUESTED} — reviewer asked for mandatory changes; blocks the next commit.</li>
 *     <li>{@link #DISMISSED}       — requester dismissed the review (takes it out of the blocking set).</li>
 * </ul>
 */
public enum ReviewStatus {
    PENDING,
    APPROVED,
    CHANGES_REQUESTED,
    DISMISSED
}
