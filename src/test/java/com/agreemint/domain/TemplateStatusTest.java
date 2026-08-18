package com.agreemint.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Only ACTIVE may generate.
 *
 * <p>Asserted on the enum itself because that is what the generation path
 * consults. A status that carried no rule would be the same mistake as the
 * hardcoded "Draft" badge this replaced — a label that looks like state and
 * enforces nothing.
 */
class TemplateStatusTest {

    @Test
    void onlyActiveAllowsGeneration() {
        assertTrue(TemplateStatus.ACTIVE.allowsGeneration());
        assertFalse(TemplateStatus.DRAFT.allowsGeneration(), "an unfinished template must not generate");
        assertFalse(TemplateStatus.ARCHIVED.allowsGeneration(), "a retired template must not generate");
    }

    @Test
    void newTemplatesStartAsDraft() {
        // The entity default. The V30 migration deliberately backfills EXISTING
        // rows to ACTIVE instead — applying this default to templates already in
        // use would have stopped every live integration the moment it ran.
        assertEquals(TemplateStatus.DRAFT, new Template().getStatus());
    }

    @Test
    void everyStateAnswersTheGenerationQuestion() {
        // A new state added without deciding this would default to whatever the
        // enum ordering gives, which is not a decision.
        for (TemplateStatus s : TemplateStatus.values()) {
            assertEquals(s == TemplateStatus.ACTIVE, s.allowsGeneration(),
                    s + " must explicitly decide whether it can generate");
        }
    }
}
