package com.agreemint.domain;

/**
 * Lifecycle state of a template, set by an author rather than derived.
 *
 * <p>Distinct from version state, which is computed: every template has a
 * committed v1 from the moment it is created ({@code TemplateService.create}
 * seeds one), so "has a version" was never a useful signal about whether a
 * template is ready to be used. This is.
 *
 * <p>The status belongs to the TEMPLATE, not to a version. ACTIVE means every
 * version of it may generate documents; ARCHIVED means none may.
 */
public enum TemplateStatus {

    /**
     * Being built. Generation is refused.
     *
     * <p>Where new templates start, so a half-finished layout cannot be
     * generated from by an integration that only knows its id.
     */
    DRAFT,

    /** In use. Documents may be generated from any of its versions. */
    ACTIVE,

    /**
     * Retired. Generation is refused, and it is hidden from the list by default.
     *
     * <p>Not deletion: the template, its versions, and every document that
     * points back to them survive. Deleting was previously the only way to get
     * a template out of the way, which took the audit trail with it.
     */
    ARCHIVED;

    /** Whether documents may be generated from a template in this state. */
    public boolean allowsGeneration() {
        return this == ACTIVE;
    }
}
