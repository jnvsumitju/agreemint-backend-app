package com.agreemint.admin;

import com.agreemint.domain.MarketplaceListing;
import com.agreemint.domain.Product;
import com.agreemint.domain.Template;
import com.agreemint.domain.TemplateVersion;
import com.agreemint.repository.MarketplaceListingRepository;
import com.agreemint.repository.ProductRepository;
import com.agreemint.repository.TemplateRepository;
import com.agreemint.repository.TemplateVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The seeder runs on every boot, so the property that matters most is that a
 * second run is a no-op. Without that, restarts would pile up template versions
 * nobody authored and duplicate the catalogue in the marketplace.
 */
class OfficialTemplateSeederTest {

    private TemplateRepository templateRepo;
    private TemplateVersionRepository versionRepo;
    private MarketplaceListingRepository listingRepo;
    private ProductRepository productRepo;
    private OfficialTemplateSeeder seeder;

    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        templateRepo = mock(TemplateRepository.class);
        versionRepo = mock(TemplateVersionRepository.class);
        listingRepo = mock(MarketplaceListingRepository.class);
        productRepo = mock(ProductRepository.class);
        seeder = new OfficialTemplateSeeder(templateRepo, versionRepo, listingRepo,
                productRepo, new ObjectMapper());

        Product product = new Product();
        product.setId(UUID.randomUUID());
        when(productRepo.findByOrgIdAndName(any(), any())).thenReturn(Optional.of(product));
        when(templateRepo.save(any(Template.class))).thenAnswer(i -> {
            Template t = i.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
        when(versionRepo.save(any(TemplateVersion.class))).thenAnswer(i -> {
            TemplateVersion v = i.getArgument(0);
            if (v.getId() == null) v.setId(UUID.randomUUID());
            return v;
        });
    }

    @Test
    void firstRunPublishesEveryBundleAsOfficial() {
        when(templateRepo.findFirstByOrgIdAndName(any(), any())).thenReturn(Optional.empty());
        when(versionRepo.findFirstByTemplateOrderByVersionNumberDesc(any())).thenReturn(Optional.empty());
        when(listingRepo.findFirstBySourceTemplateId(any())).thenReturn(Optional.empty());

        int touched = seeder.seed(orgId, "Crixaa");

        assertEquals(20, touched, "all twenty bundles should publish");
        ArgumentCaptor<MarketplaceListing> saved = ArgumentCaptor.forClass(MarketplaceListing.class);
        verify(listingRepo, org.mockito.Mockito.times(20)).save(saved.capture());
        for (MarketplaceListing l : saved.getAllValues()) {
            assertTrue(l.isOfficial(), l.getTitle() + " must be flagged official");
            assertTrue(l.isPublished(), l.getTitle() + " must be published");
            assertEquals(orgId, l.getOrgId(), "listings belong to the publisher workspace");
            assertTrue(l.getLayoutJson() != null && !l.getLayoutJson().isNull(),
                    l.getTitle() + " must carry a layout snapshot");
        }
    }

    @Test
    void aSecondRunWithUnchangedContentWritesNothing() {
        // Simulate the steady state: template exists, its latest version already
        // holds this exact layout, and the listing is live.
        when(templateRepo.findFirstByOrgIdAndName(any(), any())).thenAnswer(i -> {
            // Bind the argument to a String FIRST. Passing `i.getArgument(1)`
            // straight into String.valueOf() resolves to the char[] overload —
            // getArgument returns a free type variable, and char[] is the more
            // specific match — which threw ClassCastException for every bundle.
            // The seeder catches per-bundle failures, so all twenty were skipped
            // and this test passed while asserting nothing.
            String name = i.getArgument(1);
            Template t = new Template();
            t.setId(UUID.nameUUIDFromBytes(name.getBytes()));
            t.setName(name);
            t.setOrgId(orgId);
            return Optional.of(t);
        });
        when(versionRepo.findFirstByTemplateOrderByVersionNumberDesc(any())).thenAnswer(i -> {
            Template t = i.getArgument(0);
            TemplateVersion v = new TemplateVersion();
            v.setId(UUID.randomUUID());
            v.setTemplate(t);
            v.setVersionNumber(1);
            v.setLayoutJson(layoutOf(t.getName()));
            return Optional.of(v);
        });
        when(listingRepo.findFirstBySourceTemplateId(any())).thenAnswer(i -> {
            MarketplaceListing l = new MarketplaceListing();
            l.setId(UUID.randomUUID());
            l.setPublished(true);
            l.setOfficial(true);
            return Optional.of(l);
        });

        int touched = seeder.seed(orgId, "Crixaa");

        assertEquals(0, touched, "a steady-state boot must change nothing");
        verify(versionRepo, never()).save(any());
        verify(listingRepo, never()).save(any());
        // Proof the run was real: a bundle that throws is caught and skipped, so
        // "nothing was written" would otherwise be satisfied by writing nothing
        // because everything failed. Every bundle must have been looked up.
        verify(templateRepo, org.mockito.Mockito.times(20)).findFirstByOrgIdAndName(any(), any());
        verify(listingRepo, org.mockito.Mockito.times(20)).findFirstBySourceTemplateId(any());
    }

    /** Reads the shipped bundle so the "unchanged" comparison is against real content. */
    private com.fasterxml.jackson.databind.JsonNode layoutOf(String title) {
        try {
            var res = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                    .getResources("classpath:seed-templates/*.json");
            var mapper = new ObjectMapper();
            for (var r : res) {
                String slug = String.valueOf(r.getFilename()).replaceFirst("\\.json$", "");
                if (OfficialTemplateSeeder.titleFor(slug).equals(title)) {
                    try (var in = r.getInputStream()) {
                        return mapper.readTree(in).path("layout");
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return new ObjectMapper().createObjectNode();
    }

    @Test
    void thirdPartyListingsNeverExposeTheirSourceTemplateId() {
        // The console needs this id to let staff edit the Crixaa catalogue in
        // place. For anyone else's listing it is an internal id belonging to
        // another customer's workspace, and an id is a lookup key for whoever is
        // probing for an authorization gap — so it must not travel.
        MarketplaceListing thirdParty = new MarketplaceListing();
        thirdParty.setId(UUID.randomUUID());
        thirdParty.setTitle("Someone else's template");
        thirdParty.setOfficial(false);
        thirdParty.setPublished(true);
        thirdParty.setSourceTemplateId(UUID.randomUUID());

        MarketplaceListing ours = new MarketplaceListing();
        ours.setId(UUID.randomUUID());
        ours.setTitle("GST Invoice");
        ours.setOfficial(true);
        ours.setPublished(true);
        UUID ourTemplate = UUID.randomUUID();
        ours.setSourceTemplateId(ourTemplate);

        when(listingRepo.findByPublishedTrueOrderByCreatedAtDesc())
                .thenReturn(java.util.List.of(thirdParty, ours));

        var service = new com.agreemint.service.MarketplaceService(
                listingRepo, templateRepo, versionRepo, productRepo);
        var rows = service.listPublished(false);

        var third = rows.stream().filter(r -> !r.official()).findFirst().orElseThrow();
        var official = rows.stream().filter(r -> r.official()).findFirst().orElseThrow();
        assertEquals(null, third.sourceTemplateId(),
                "a third-party listing must not disclose its source template");
        assertEquals(ourTemplate, official.sourceTemplateId(),
                "staff need this to edit the first-party catalogue in place");
    }

    @Test
    void titlesAreReadableAndKeepTheAcronymsCustomersSearchFor() {
        assertEquals("GST Invoice", OfficialTemplateSeeder.titleFor("free-gst-invoice-template"));
        assertEquals("NDA", OfficialTemplateSeeder.titleFor("free-nda-template"));
        assertEquals("ID Card", OfficialTemplateSeeder.titleFor("free-id-card-template"));
        assertEquals("Offer Letter", OfficialTemplateSeeder.titleFor("free-offer-letter-template"));
    }

    @Test
    void categoriesMatchTheMarketingSiteGrouping() {
        assertEquals("Finance", OfficialTemplateSeeder.categoryFor("free-gst-invoice-template"));
        assertEquals("HR", OfficialTemplateSeeder.categoryFor("free-offer-letter-template"));
        assertEquals("Education", OfficialTemplateSeeder.categoryFor("free-marksheet-template"));
        assertEquals("Business", OfficialTemplateSeeder.categoryFor("free-nda-template"));
    }
}
