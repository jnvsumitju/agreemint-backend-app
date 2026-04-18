package com.agreemint.domain;

/**
 * How a generated document was produced.
 *
 * <p>{@link #UI_GENERATED} documents flow through the in-app lifecycle
 * (draft → review → approved → ...). {@link #API_GENERATED} documents come
 * from the {@code /api/v1/templates/.../generate} endpoint — customers using
 * the developer API run their own review/approval pipeline on their side, so
 * we skip our lifecycle entirely for those and leave {@code lifecycle_status}
 * null.
 */
public enum DocumentSource {
    UI_GENERATED,
    API_GENERATED
}
