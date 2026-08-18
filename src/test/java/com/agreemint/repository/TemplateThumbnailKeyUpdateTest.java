package com.agreemint.repository;

import com.agreemint.domain.Template;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The thumbnail-key writes, run the way the background thread actually runs them.
 *
 * <p>{@code NOT_SUPPORTED} is the entire point. {@code @DataJpaTest} normally
 * wraps each test in a transaction, which would supply the ambient transaction
 * these queries need and hide whether they carry their own. Rendering now
 * happens after the commit transaction has closed, on a pool thread with no
 * transaction at all — so that is the condition worth testing.
 *
 * <p>This exact mistake has already shipped here once: StaffExportRepository's
 * {@code claim()} was a {@code @Modifying} query with no {@code @Transactional},
 * Spring Data does not extend the repository's class-level transaction to custom
 * query methods, and it threw on every call from the scheduler — the export
 * feature had never completed a single time.
 *
 * <p>The other property is why these are bulk UPDATEs at all: Template carries
 * an {@code @Version} optimistic lock, and a derived image finishing must not
 * make a concurrent human edit fail.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TemplateThumbnailKeyUpdateTest {

    @Autowired private TemplateRepository templateRepo;

    private Template saved(String draftKey, String committedKey) {
        Template t = new Template();
        t.setName("Offer Letter");
        t.setOrgId(UUID.randomUUID());
        t.setDraftThumbnailKey(draftKey);
        t.setThumbnailKey(committedKey);
        return templateRepo.save(t);
    }

    @Test
    void aCommitCaptureRecordsTheKeyAndClearsTheDraftOne() {
        Template t = saved("templates/in-progress.png", null);

        int rows = templateRepo.updateThumbnailKeys(
                t.getId(), "templates/committed.png", null, Instant.now());

        assertEquals(1, rows);
        Template after = templateRepo.findById(t.getId()).orElseThrow();
        assertEquals("templates/committed.png", after.getThumbnailKey());
        // commitDraft deletes the draft row, so an in-progress image that
        // outlived it would show edits that no longer exist.
        assertNull(after.getDraftThumbnailKey());
        assertNotNull(after.getThumbnailUpdatedAt());
    }

    @Test
    void aDraftCaptureLeavesTheCommittedKeyUntouched() {
        Template t = saved(null, "templates/committed.png");

        int rows = templateRepo.updateDraftThumbnailKey(
                t.getId(), "templates/in-progress.png", Instant.now());

        assertEquals(1, rows);
        Template after = templateRepo.findById(t.getId()).orElseThrow();
        assertEquals("templates/in-progress.png", after.getDraftThumbnailKey());
        assertEquals("templates/committed.png", after.getThumbnailKey(),
                "an uncommitted edit must not overwrite what the last version looked like");
    }

    @Test
    void theWriteDoesNotBumpTheOptimisticLockVersion() {
        Template t = saved(null, null);
        Long before = t.getVersion();
        assertNotNull(before, "fixture assumes Template is version-locked");

        templateRepo.updateThumbnailKeys(t.getId(), "templates/x.png", null, Instant.now());

        Template after = templateRepo.findById(t.getId()).orElseThrow();
        assertEquals(before, after.getVersion(),
                "a rendered image is not a user edit; bumping the version would make "
                        + "someone else's concurrent save fail for a reason they could not see");
    }

    @Test
    void aTemplateThatVanishedMidRenderUpdatesNothingRatherThanThrowing() {
        // The row count is how the caller learns this, which is why there is no
        // existsById check first — that would be a second query on every capture
        // to detect a case that costs nothing.
        assertEquals(0, templateRepo.updateThumbnailKeys(
                UUID.randomUUID(), "templates/x.png", null, Instant.now()));
        assertEquals(0, templateRepo.updateDraftThumbnailKey(
                UUID.randomUUID(), "templates/x.png", Instant.now()));
    }

    @Test
    void onlyTheTargetedRowIsTouched() {
        Template target = saved(null, "templates/target-old.png");
        Template bystander = saved("templates/bystander-draft.png", "templates/bystander.png");

        templateRepo.updateThumbnailKeys(target.getId(), "templates/target-new.png", null, Instant.now());

        Template other = templateRepo.findById(bystander.getId()).orElseThrow();
        assertEquals("templates/bystander.png", other.getThumbnailKey());
        assertEquals("templates/bystander-draft.png", other.getDraftThumbnailKey());
    }
}
