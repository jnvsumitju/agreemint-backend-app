package com.agreemint.service;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.domain.MarketplaceListing;
import com.agreemint.domain.Product;
import com.agreemint.domain.Template;
import com.agreemint.domain.TemplateVersion;
import com.agreemint.repository.MarketplaceListingRepository;
import com.agreemint.repository.ProductRepository;
import com.agreemint.repository.TemplateRepository;
import com.agreemint.repository.TemplateVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Cover for installing and publishing a marketplace listing.
 *
 * <p>Both operations were broken in ways that looked fine from the outside.
 * Installing created a template row and copied no content, so every install
 * produced an empty document under a "cloned successfully" banner. Publishing
 * accepted whatever {@code sourceTemplateId} the caller sent.
 */
class MarketplaceServiceTest {

    private MarketplaceListingRepository listingRepo;
    private TemplateRepository templateRepo;
    private TemplateVersionRepository versionRepo;
    private ProductRepository productRepo;
    private MarketplaceService service;

    private final UUID sourceOrgId = UUID.randomUUID();
    private final UUID targetOrgId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final List<TemplateVersion> savedVersions = new ArrayList<>();
    private final List<Product> savedProducts = new ArrayList<>();

    private Template source;
    private JsonNode layout;

    @BeforeEach
    void setUp() throws Exception {
        listingRepo = mock(MarketplaceListingRepository.class);
        templateRepo = mock(TemplateRepository.class);
        versionRepo = mock(TemplateVersionRepository.class);
        productRepo = mock(ProductRepository.class);
        savedVersions.clear();
        savedProducts.clear();

        layout = new ObjectMapper().readTree("{\"pages\":[{\"elements\":[{\"id\":\"a\"}]}]}");

        source = new Template();
        source.setId(UUID.randomUUID());
        source.setName("Invoice");
        source.setOrgId(sourceOrgId);
        source.setProductId(UUID.randomUUID());
        when(templateRepo.findById(source.getId())).thenReturn(Optional.of(source));
        when(templateRepo.save(any())).thenAnswer((i) -> {
            Template t = i.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
        when(versionRepo.save(any())).thenAnswer((i) -> {
            savedVersions.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(productRepo.save(any())).thenAnswer((i) -> {
            Product p = i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            savedProducts.add(p);
            return p;
        });

        service = new MarketplaceService(listingRepo, templateRepo, versionRepo, productRepo);
    }

    /** A published listing, i.e. one carrying its snapshot — see publish(). */
    private MarketplaceListing listing(UUID sourceTemplateId) {
        MarketplaceListing l = new MarketplaceListing();
        l.setId(UUID.randomUUID());
        l.setSourceTemplateId(sourceTemplateId);
        l.setTitle("Invoice");
        l.setLayoutJson(layout);
        l.setPublished(true);
        when(listingRepo.findById(l.getId())).thenReturn(Optional.of(l));
        return l;
    }

    /** A listing from before V25, or one whose publish never snapshotted. */
    private MarketplaceListing listingWithoutSnapshot() {
        MarketplaceListing l = listing(source.getId());
        l.setLayoutJson(null);
        return l;
    }

    private void sourceHasVersion() {
        TemplateVersion v = new TemplateVersion();
        v.setTemplate(source);
        v.setVersionNumber(7);
        v.setLayoutJson(layout);
        when(versionRepo.findFirstByTemplateOrderByVersionNumberDesc(source))
                .thenReturn(Optional.of(v));
    }

    private void targetHasProduct() {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setOrgId(targetOrgId);
        p.setName("Existing");
        when(productRepo.findByOrgIdOrderByNameAsc(targetOrgId)).thenReturn(List.of(p));
    }

    // ── installing ────────────────────────────────────────────────────────

    @Test
    void installCopiesTheSourceContent() {
        targetHasProduct();

        Template clone = service.cloneTemplate(listing(source.getId()).getId(), targetOrgId, ownerId);

        // The entire point: a template row with no version is a blank document.
        assertEquals(1, savedVersions.size(), "install must copy the source's content");
        assertEquals(layout, savedVersions.get(0).getLayoutJson());
        assertSame(clone, savedVersions.get(0).getTemplate());
    }

    @Test
    void theCopiedVersionStartsAtOne() {
        sourceHasVersion();
        targetHasProduct();

        service.cloneTemplate(listing(source.getId()).getId(), targetOrgId, ownerId);

        // The source was on v7; the clone is a new template with its own history.
        assertEquals(1, savedVersions.get(0).getVersionNumber());
    }

    @Test
    void theCloneLandsInTheInstallingOrgNotTheAuthors() {
        sourceHasVersion();
        targetHasProduct();

        Template clone = service.cloneTemplate(listing(source.getId()).getId(), targetOrgId, ownerId);

        assertEquals(targetOrgId, clone.getOrgId());
        assertEquals(ownerId, clone.getOwnerId());
    }

    @Test
    void theCloneGetsAProductInItsOwnOrg() {
        sourceHasVersion();
        targetHasProduct();

        Template clone = service.cloneTemplate(listing(source.getId()).getId(), targetOrgId, ownerId);

        assertNotNull(clone.getProductId(), "product_id is RESTRICT and treated as required");
        assertNotEquals(source.getProductId(), clone.getProductId(),
                "products are per-org; reusing the source's points at another workspace");
    }

    @Test
    void anOrgWithNoProductsGetsOneRatherThanAFailedInstall() {
        sourceHasVersion();
        when(productRepo.findByOrgIdOrderByNameAsc(targetOrgId)).thenReturn(List.of());

        Template clone = service.cloneTemplate(listing(source.getId()).getId(), targetOrgId, ownerId);

        assertEquals(1, savedProducts.size());
        assertEquals(targetOrgId, savedProducts.get(0).getOrgId());
        assertEquals(savedProducts.get(0).getId(), clone.getProductId());
    }



    @Test
    void installIncrementsTheCount() {
        sourceHasVersion();
        targetHasProduct();
        MarketplaceListing l = listing(source.getId());

        service.cloneTemplate(l.getId(), targetOrgId, ownerId);

        assertEquals(1, l.getInstallCount());
    }

    // ── publishing ────────────────────────────────────────────────────────

    @Test
    void publishSnapshotsTheContentOntoTheListing() {
        sourceHasVersion();

        service.publish(ownerId, "Ada", source.getId(), "Invoice", "desc", "Finance", "tag");

        // The listing must carry its own copy: the publisher keeps editing the
        // template, and installers must not silently receive those edits.
        verify(listingRepo).save(argThat((MarketplaceListing l) ->
                layout.equals(l.getLayoutJson())));
    }

    @Test
    void publishingATemplateWithNoCommittedVersionIsRejected() {
        when(versionRepo.findFirstByTemplateOrderByVersionNumberDesc(source))
                .thenReturn(Optional.empty());

        assertThrows(BadRequestException.class,
                () -> service.publish(ownerId, "Ada", source.getId(), "t", "d", "c", "x"));
        verify(listingRepo, never()).save(any());
    }

    @Test
    void installReadsTheSnapshotNotTheLiveTemplate() {
        // Publisher edited their template after publishing. Installers get what
        // was published, not the current draft.
        targetHasProduct();

        service.cloneTemplate(listing(source.getId()).getId(), targetOrgId, ownerId);

        assertEquals(layout, savedVersions.get(0).getLayoutJson());
        verify(versionRepo, never()).findFirstByTemplateOrderByVersionNumberDesc(any());
    }

    @Test
    void installStillWorksAfterTheSourceTemplateIsDeleted() {
        // source_template_id is ON DELETE SET NULL. Before snapshotting this
        // was an unrecoverable listing; now the content lives on the listing.
        targetHasProduct();
        MarketplaceListing l = listing(null);

        Template clone = service.cloneTemplate(l.getId(), targetOrgId, ownerId);

        assertNotNull(clone);
        assertEquals(layout, savedVersions.get(0).getLayoutJson());
    }

    @Test
    void aListingWithNoSnapshotCannotBeInstalled() {
        MarketplaceListing l = listingWithoutSnapshot();
        targetHasProduct();

        assertThrows(NotFoundException.class,
                () -> service.cloneTemplate(l.getId(), targetOrgId, ownerId));
    }

    @Test
    void aWithdrawnListingCannotBeInstalled() {
        // Withdrawal is the ONLY recall path — there is no moderation queue.
        // Removing it from the catalogue while anyone holding the id can still
        // install the content makes the control decorative.
        MarketplaceListing l = listing(source.getId());
        l.setPublished(false);
        targetHasProduct();

        assertThrows(NotFoundException.class,
                () -> service.cloneTemplate(l.getId(), targetOrgId, ownerId));
        assertTrue(savedVersions.isEmpty());
    }

    @Test
    void aWithdrawnListingIsNotReadableById() {
        // A title and description can name a real client on their own.
        MarketplaceListing l = listing(source.getId());
        l.setPublished(false);

        assertThrows(NotFoundException.class, () -> service.getById(l.getId()));
    }

    @Test
    void theResponseReportsWhetherItIsStillPublished() {
        // The console renders its Withdraw button off this field; without it
        // every listing reads as "Withdrawn" and withdrawal is unreachable.
        MarketplaceListing l = listing(source.getId());

        assertTrue(service.getById(l.getId()).published());
    }

    @Test
    void withdrawUnpublishesWithoutDeleting() {
        MarketplaceListing l = listing(source.getId());
        l.setPublished(true);
        l.setInstallCount(12);

        service.withdraw(l.getId());

        assertFalse(l.isPublished());
        // Install history survives: people already have copies.
        assertEquals(12, l.getInstallCount());
        verify(listingRepo, never()).delete(any());
    }

    @Test
    void publishAttributesTheListingToTheTemplatesOrg() {
        sourceHasVersion();
        // Not the caller's current org: someone in two workspaces would
        // otherwise file the listing under whichever one they had open.
        service.publish(ownerId, "Ada", source.getId(), "Invoice", "desc", "Finance", "tag");

        verify(listingRepo).save(argThat((MarketplaceListing l) ->
                sourceOrgId.equals(l.getOrgId())));
    }

    @Test
    void anOrglessTemplateCannotBePublished() {
        // Reachable: V14 leaves a template orphaned when its creator has no
        // membership, and assertTemplateAccess lets the owner through on
        // ownerId alone — so the publish gate would pass. The resulting listing
        // would be public forever, because withdraw authorizes against the
        // owning org and ownerOrgId() would be null for every caller.
        source.setOrgId(null);
        sourceHasVersion();

        assertThrows(BadRequestException.class,
                () -> service.publish(ownerId, "Ada", source.getId(), "t", "d", "c", "x"));
        verify(listingRepo, never()).save(any());
    }

    @Test
    void publishingTheSameTemplateTwiceIsRejected() {
        // Two identical live listings, each needing its own withdrawal — easy to
        // trigger when the console gave no success signal.
        sourceHasVersion();
        when(listingRepo.existsBySourceTemplateIdAndPublishedTrue(source.getId())).thenReturn(true);

        assertThrows(BadRequestException.class,
                () -> service.publish(ownerId, "Ada", source.getId(), "t", "d", "c", "x"));
        verify(listingRepo, never()).save(any());
    }

    @Test
    void republishingAfterWithdrawalIsAllowed() {
        // Only a currently-live duplicate is refused; withdrawing and putting it
        // back must keep working.
        sourceHasVersion();
        when(listingRepo.existsBySourceTemplateIdAndPublishedTrue(source.getId())).thenReturn(false);

        assertDoesNotThrow(
                () -> service.publish(ownerId, "Ada", source.getId(), "t", "d", "c", "x"));
        verify(listingRepo).save(any());
    }

    @Test
    void publishingAnUnknownTemplateIsRejected() {
        UUID missing = UUID.randomUUID();
        when(templateRepo.findById(missing)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> service.publish(ownerId, "Ada", missing, "t", "d", "c", "x"));
        verify(listingRepo, never()).save(any());
    }
}
