package com.agreemint.service;

import com.agreemint.domain.Organization;
import com.agreemint.domain.Template;
import com.agreemint.pdf.PdfRendererService;
import com.agreemint.repository.OrganizationRepository;
import com.agreemint.repository.TemplateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which thumbnails become world-readable, and what happens when one fails.
 *
 * <p>Two properties, and the first one is a privacy boundary rather than a
 * feature. A thumbnail is a picture of page one of a real document — an offer
 * letter with a salary on it, an invoice with a customer's address. Everything
 * lands in a private bucket behind a presigned URL except for the first-party
 * templates Crixaa publishes on its own marketing site, which go to a bucket
 * with no auth in front of it at all. The org check is the only thing
 * separating those two, so it is tested here for the ways it could be wrong:
 * a customer org, a missing org, an org whose slug merely resembles the
 * publisher's, and a draft capture (which must never publish, first-party or
 * not — an in-progress edit is not a published document).
 *
 * <p>The second is that none of this can cost an author their commit. The
 * thumbnail is derived and can be re-made from the layout at any time; the
 * version is the work. {@code commitDraft} calls into here and does not guard
 * the call, so "never throws" is a contract this class owes its caller, and
 * every way it can fail is exercised below.
 */
class TemplateThumbnailPublishTest {

    private static final String PUBLISHER = "crixaa";
    private static final String SLUG = "free-offer-letter-template";

    private PdfRendererService renderer;
    private R2StorageService storage;
    private TemplateRepository templateRepo;
    private OrganizationRepository orgRepo;
    private TemplateThumbnailService service;

    private final UUID templateId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Minimal bytes that PDFBox will load as a one-page document. */
    private static byte[] onePagePdf() throws Exception {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            var out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private Template template() {
        Template t = new Template();
        t.setId(templateId);
        t.setName("Offer Letter");
        t.setOrgId(orgId);
        // Set even on the customer-org cases: publishing must be refused
        // because of who owns it, not because it happened to lack a slug.
        t.setPublicSlug(SLUG);
        return t;
    }

    private void orgWithSlug(String slug) {
        Organization o = new Organization();
        o.setId(orgId);
        o.setSlug(slug);
        when(orgRepo.findById(orgId)).thenReturn(Optional.of(o));
    }

    @BeforeEach
    void setUp() throws Exception {
        renderer = mock(PdfRendererService.class);
        when(renderer.render(any(), any())).thenReturn(onePagePdf());

        storage = mock(R2StorageService.class);
        when(storage.putPublicThumbnail(any(), any(), any()))
                .thenReturn("https://cdn.example/templates/x.png");

        templateRepo = mock(TemplateRepository.class);
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(template()));

        orgRepo = mock(OrganizationRepository.class);

        service = new TemplateThumbnailService(renderer, storage, templateRepo, orgRepo, PUBLISHER);
    }

    // ── the privacy boundary ──────────────────────────────────────────────────

    @Test
    void aCustomersCommittedThumbnailNeverReachesThePublicBucket() {
        orgWithSlug("acme-industries");

        service.captureCommitted(templateId, mapper.createObjectNode(), null);

        verify(storage, times(1)).putThumbnail(any(), any(), any());
        verify(storage, never()).putPublicThumbnail(any(), any(), any());
    }

    @Test
    void thePublishersCommittedThumbnailIsMirroredPublicly() {
        orgWithSlug(PUBLISHER);

        service.captureCommitted(templateId, mapper.createObjectNode(), null);

        verify(storage, times(1)).putThumbnail(any(), any(), any());
        // Keyed by slug, not id: crixaa.com builds this URL from the slug in
        // its own frontmatter and has no way to learn a UUID.
        verify(storage, times(1))
                .putPublicThumbnail(eq("templates/" + SLUG + ".png"), any(), eq("image/png"));
    }

    @Test
    void aSlugThatMerelyResemblesThePublisherDoesNotCount() {
        // Anyone can name their org. If this matched on prefix or substring,
        // "crixaa-partners" would be enough to publish a customer's documents.
        for (String impostor : new String[] { "crixaa-partners", "not-crixaa", "crixaaa", "crixa" }) {
            orgWithSlug(impostor);
            service.captureCommitted(templateId, mapper.createObjectNode(), null);
        }
        verify(storage, never()).putPublicThumbnail(any(), any(), any());
    }

    @Test
    void theMatchIgnoresCaseSoTheSeededOrgIsRecognised() {
        // The bootstrap runner writes the slug and the property supplies the
        // expected value; a case difference between the two would silently stop
        // publishing without failing anything.
        orgWithSlug("Crixaa");

        service.captureCommitted(templateId, mapper.createObjectNode(), null);

        verify(storage, times(1)).putPublicThumbnail(any(), any(), any());
    }

    @Test
    void anUnresolvableOrgIsTreatedAsNotFirstParty() {
        when(orgRepo.findById(orgId)).thenReturn(Optional.empty());

        service.captureCommitted(templateId, mapper.createObjectNode(), null);

        // Fail closed: not knowing who owns it is not a reason to publish it.
        verify(storage, never()).putPublicThumbnail(any(), any(), any());
    }

    @Test
    void aTemplateWithNoOrgIsTreatedAsNotFirstParty() {
        Template orphan = template();
        orphan.setOrgId(null);
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(orphan));

        service.captureCommitted(templateId, mapper.createObjectNode(), null);

        verify(storage, never()).putPublicThumbnail(any(), any(), any());
    }

    @Test
    void aPublisherTemplateWithNoSlugIsNotPublished() {
        // A staff member's own new template in the publisher org. Nothing on
        // crixaa.com links to it, so an object under a UUID key would be an
        // orphan nobody ever fetches.
        Template unslugged = template();
        unslugged.setPublicSlug(null);
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(unslugged));
        orgWithSlug(PUBLISHER);

        service.captureCommitted(templateId, mapper.createObjectNode(), null);

        verify(storage, times(1)).putThumbnail(any(), any(), any());
        verify(storage, never()).putPublicThumbnail(any(), any(), any());
    }

    @Test
    void aDraftCaptureNeverPublishesEvenForThePublisher() {
        // Drafts are captured every sixty seconds while someone is editing.
        // Mirroring those would put half-finished edits on the marketing site,
        // and would do it for work the author has not committed to.
        orgWithSlug(PUBLISHER);

        service.captureDraft(templateId, mapper.createObjectNode(), null);

        verify(storage, times(1)).putThumbnail(any(), any(), any());
        verify(storage, never()).putPublicThumbnail(any(), any(), any());
    }

    @Test
    void thePublicCopyIsRenderedLargerThanTheConsolesOwn() throws Exception {
        // They are shown at different sizes. crixaa.com crops these to 4:3 and
        // top-aligns them, so most of the page is thrown away and what remains
        // has to fill a card wider than the console's. Publishing the same
        // 600px image to both would look soft on exactly the page whose job is
        // to sell the template.
        orgWithSlug(PUBLISHER);

        service.captureCommitted(templateId, mapper.createObjectNode(), null);

        var priv = org.mockito.ArgumentCaptor.forClass(byte[].class);
        var pub = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(storage).putThumbnail(any(), priv.capture(), any());
        verify(storage).putPublicThumbnail(any(), pub.capture(), any());

        var privImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(priv.getValue()));
        var pubImg = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(pub.getValue()));
        assertNotNull(privImg);
        assertNotNull(pubImg);

        int privEdge = Math.max(privImg.getWidth(), privImg.getHeight());
        int pubEdge = Math.max(pubImg.getWidth(), pubImg.getHeight());
        assertEquals(600, privEdge, "the console copy is capped at 600");
        assertEquals(900, pubEdge, "the public copy is capped at 900");

        // Both must come from one rasterise at a DPI above either cap — if the
        // page were rendered at 72 DPI the 900px copy would be an upscale of a
        // 792px image, i.e. blurrier than the small one rather than sharper.
        assertEquals(1, org.mockito.Mockito.mockingDetails(renderer).getInvocations().size(),
                "one render for both sizes, not one per size");
        assertEquals(
                (double) privImg.getHeight() / privImg.getWidth(),
                (double) pubImg.getHeight() / pubImg.getWidth(),
                0.01,
                "same page, so same aspect ratio at both sizes");
    }

    // ── what each capture records ─────────────────────────────────────────────

    @Test
    void committingReplacesTheCommittedKeyAndDropsTheDraftOne() {
        Template t = template();
        t.setDraftThumbnailKey("templates/" + templateId + ".png");
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(t));
        orgWithSlug("acme-industries");

        service.captureCommitted(templateId, mapper.createObjectNode(), null);

        assertEquals(TemplateThumbnailService.privateKey(templateId), t.getThumbnailKey());
        // commitDraft deletes the draft row, so a lingering draft key would make
        // the list show an in-progress image for a template with no edits in it.
        assertNull(t.getDraftThumbnailKey(), "the draft preview must not outlive the draft");
        assertNotNull(t.getThumbnailUpdatedAt());
        verify(templateRepo).save(t);
    }

    @Test
    void aDraftCaptureLeavesTheCommittedThumbnailAlone() {
        Template t = template();
        t.setThumbnailKey("templates/previously-committed.png");
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(t));

        service.captureDraft(templateId, mapper.createObjectNode(), null);

        assertEquals("templates/previously-committed.png", t.getThumbnailKey(),
                "an uncommitted edit must not overwrite what the last version looked like");
        assertEquals(TemplateThumbnailService.privateKey(templateId), t.getDraftThumbnailKey());
    }

    // ── survivability: a commit must not depend on an image ───────────────────

    @Test
    void aRendererFailureDoesNotPropagateOutOfACommitCapture() throws Exception {
        when(renderer.render(any(), any())).thenThrow(new RuntimeException("renderer exploded"));

        assertDoesNotThrow(() -> service.captureCommitted(templateId, mapper.createObjectNode(), null));

        verify(templateRepo, never()).save(any());
        verify(storage, never()).putThumbnail(any(), any(), any());
    }

    @Test
    void aStorageFailureDoesNotPropagateAndLeavesNoKeyBehind() {
        doThrow(new RuntimeException("R2 unreachable"))
                .when(storage).putThumbnail(any(), any(), any());
        Template t = template();
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(t));

        assertDoesNotThrow(() -> service.captureCommitted(templateId, mapper.createObjectNode(), null));

        // Recording a key for an object that was never stored would produce a
        // presigned URL to nothing, which is worse than no thumbnail at all.
        assertNull(t.getThumbnailKey());
        verify(templateRepo, never()).save(any());
    }

    @Test
    void aFailureMirroringToThePublicBucketStillKeepsThePrivateOne() {
        orgWithSlug(PUBLISHER);
        when(storage.putPublicThumbnail(any(), any(), any()))
                .thenThrow(new RuntimeException("public bucket denied"));
        Template t = template();
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(t));

        assertDoesNotThrow(() -> service.captureCommitted(templateId, mapper.createObjectNode(), null));

        assertEquals(TemplateThumbnailService.privateKey(templateId), t.getThumbnailKey(),
                "the console's own thumbnail should survive a marketing-site upload failing");
    }

    @Test
    void aDatabaseFailureRecordingTheKeyDoesNotPropagate() {
        // The one that would actually cost a commit: captureCommitted joins the
        // caller's transaction, so a save that blows up here surfaces inside
        // commitDraft unless it is caught.
        when(templateRepo.save(any())).thenThrow(new RuntimeException("constraint violation"));

        assertDoesNotThrow(() -> service.captureCommitted(templateId, mapper.createObjectNode(), null));
    }

    @Test
    void aTemplateThatVanishedMidCommitIsNotAnError() {
        when(templateRepo.findById(templateId)).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.captureCommitted(templateId, mapper.createObjectNode(), null));

        verify(storage, never()).putPublicThumbnail(any(), any(), any());
    }
}
