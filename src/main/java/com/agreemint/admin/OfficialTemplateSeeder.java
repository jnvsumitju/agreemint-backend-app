package com.agreemint.admin;

import com.agreemint.domain.MarketplaceListing;
import com.agreemint.domain.Product;
import com.agreemint.domain.Template;
import com.agreemint.domain.TemplateVersion;
import com.agreemint.repository.MarketplaceListingRepository;
import com.agreemint.repository.ProductRepository;
import com.agreemint.repository.TemplateRepository;
import com.agreemint.repository.TemplateVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Publishes the twenty free templates into the marketplace as first-party listings.
 *
 * <p>The bundles arrive as classpath resources under {@code seed-templates/},
 * emitted by the console's generator so the sandbox at {@code /try/:slug} and
 * the marketplace cannot drift apart. Each becomes a real {@link Template} in
 * the publisher workspace with a committed {@link TemplateVersion}, then a
 * published listing snapshotted from that version — exactly the shape an author
 * would produce by hand, which is what lets staff edit them afterwards with no
 * special code path.
 *
 * <p><strong>Idempotent by design</strong>, because this runs on every boot. A
 * template is identified by (org, name); a new version is committed only when
 * the layout actually differs, so restarting does not pile up versions nobody
 * made. The listing is refreshed in place rather than republished, so its id —
 * which the console may have linked to — survives.
 */
@Service
public class OfficialTemplateSeeder {

    private static final Logger log = LoggerFactory.getLogger(OfficialTemplateSeeder.class);

    /** Where installs land in the publisher workspace. */
    private static final String PRODUCT_NAME = "Free templates";
    private static final String SEED_PATTERN = "classpath:seed-templates/*.json";

    private final TemplateRepository templateRepo;
    private final TemplateVersionRepository versionRepo;
    private final MarketplaceListingRepository listingRepo;
    private final ProductRepository productRepo;
    private final ObjectMapper objectMapper;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public OfficialTemplateSeeder(TemplateRepository templateRepo,
                                  TemplateVersionRepository versionRepo,
                                  MarketplaceListingRepository listingRepo,
                                  ProductRepository productRepo,
                                  ObjectMapper objectMapper,
                                  org.springframework.transaction.support.TransactionTemplate tx) {
        this.templateRepo = templateRepo;
        this.versionRepo = versionRepo;
        this.listingRepo = listingRepo;
        this.productRepo = productRepo;
        this.objectMapper = objectMapper;
        this.tx = tx;
    }

    /**
     * @return how many listings were created or refreshed.
     *
     * <p>Each bundle commits in its own transaction. Wrapping the whole batch
     * in one meant a single persistence failure marked it rollback-only while
     * the loop cheerfully carried on and logged success — and everything was
     * then discarded at commit, including the nineteen that were fine.
     */
    public int seed(UUID publisherOrgId, String publisherName) {
        List<Resource> bundles = loadBundles();
        if (bundles.isEmpty()) {
            log.warn("[template-seed] No bundles under seed-templates/ — nothing to publish. "
                    + "Run the console's generate-try-templates script.");
            return 0;
        }
        UUID productId = tx.execute(status -> ensureProduct(publisherOrgId));
        int touched = 0;
        for (Resource bundle : bundles) {
            try {
                Boolean changed = tx.execute(status -> {
                    try {
                        return seedOne(bundle, publisherOrgId, publisherName, productId);
                    } catch (Exception e) {
                        // Roll back just this bundle, then rethrow so the outer
                        // catch can log which one and move to the next.
                        status.setRollbackOnly();
                        throw new IllegalStateException(e);
                    }
                });
                if (Boolean.TRUE.equals(changed)) touched++;
            } catch (Exception e) {
                // One malformed bundle must not stop the other nineteen, and must
                // not abort startup. Loud, then carry on.
                log.error("[template-seed] Skipped '{}': {}", bundle.getFilename(), e.toString());
            }
        }
        log.info("[template-seed] {} of {} first-party listing(s) created or refreshed.",
                touched, bundles.size());
        return touched;
    }

    private List<Resource> loadBundles() {
        try {
            Resource[] found = new PathMatchingResourcePatternResolver()
                    .getResources(SEED_PATTERN);
            List<Resource> sorted = new ArrayList<>(List.of(found));
            // Deterministic order so log output and version numbers are stable
            // across boots on different machines.
            sorted.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));
            return sorted;
        } catch (IOException e) {
            log.warn("[template-seed] Could not read seed-templates/: {}", e.toString());
            return List.of();
        }
    }

    private UUID ensureProduct(UUID orgId) {
        return productRepo.findByOrgIdAndName(orgId, PRODUCT_NAME)
                .map(Product::getId)
                .orElseGet(() -> {
                    Product p = new Product();
                    p.setOrgId(orgId);
                    p.setName(PRODUCT_NAME);
                    return productRepo.save(p).getId();
                });
    }

    private boolean seedOne(Resource bundle, UUID orgId, String publisherName, UUID productId)
            throws IOException {
        JsonNode payload;
        try (InputStream in = bundle.getInputStream()) {
            payload = objectMapper.readTree(in);
        }
        JsonNode layout = payload.path("layout");
        if (layout.isMissingNode() || layout.isNull()) {
            throw new IllegalArgumentException("bundle has no layout");
        }
        JsonNode variables = payload.path("variableValues");
        String slug = String.valueOf(bundle.getFilename()).replaceFirst("\\.json$", "");
        String title = titleFor(slug);

        Template template = templateRepo.findFirstByOrgIdAndName(orgId, title)
                .orElseGet(() -> {
                    Template t = new Template();
                    t.setName(title);
                    t.setOrgId(orgId);
                    t.setProductId(productId);
                    t.setCreatedBy(publisherName);
                    return templateRepo.save(t);
                });

        // Only commit a version when the content actually moved. Without this,
        // every restart would add an identical version and the History tab would
        // fill with changes nobody made.
        Optional<TemplateVersion> latest =
                versionRepo.findFirstByTemplateOrderByVersionNumberDesc(template);
        boolean changed = latest.isEmpty() || !layout.equals(latest.get().getLayoutJson());
        TemplateVersion version;
        if (changed) {
            version = new TemplateVersion();
            version.setTemplate(template);
            version.setVersionNumber(latest.map(v -> v.getVersionNumber() + 1).orElse(1));
            version.setLayoutJson(layout);
            version.setVariables(variables);
            version = versionRepo.save(version);
        } else {
            version = latest.get();
        }

        MarketplaceListing listing = listingRepo.findFirstBySourceTemplateId(template.getId())
                .orElseGet(MarketplaceListing::new);
        boolean isNew = listing.getId() == null;
        if (!isNew && !changed && listing.isPublished()) {
            return false;
        }
        listing.setType("TEMPLATE");
        listing.setTitle(title);
        listing.setDescription(descriptionFor(title));
        listing.setAuthorName(publisherName);
        listing.setOrgId(orgId);
        listing.setSourceTemplateId(template.getId());
        listing.setSourceVersionId(version.getId());
        listing.setLayoutJson(layout);
        listing.setVariables(variables);
        listing.setCategory(categoryFor(slug));
        listing.setOfficial(true);
        listing.setPublished(true);
        listing.setUpdatedAt(Instant.now());
        listingRepo.save(listing);
        return true;
    }

    /** `free-gst-invoice-template` → `GST Invoice`. */
    static String titleFor(String slug) {
        String core = slug.replaceFirst("^free-", "").replaceFirst("-template$", "");
        StringBuilder sb = new StringBuilder();
        for (String word : core.split("-")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            // These read wrong in title case and are the ones customers search for.
            switch (word) {
                case "gst" -> sb.append("GST");
                case "nda" -> sb.append("NDA");
                case "id" -> sb.append("ID");
                default -> sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return sb.toString();
    }

    static String categoryFor(String slug) {
        if (slug.contains("invoice") || slug.contains("receipt") || slug.contains("quotation")
                || slug.contains("purchase-order") || slug.contains("statement")) return "Finance";
        if (slug.contains("offer-letter") || slug.contains("experience") || slug.contains("salary")
                || slug.contains("joining") || slug.contains("relieving")) return "HR";
        if (slug.contains("certificate") || slug.contains("marksheet") || slug.contains("id-card")
                || slug.contains("admit")) return "Education";
        return "Business";
    }

    private static String descriptionFor(String title) {
        return title + " — a free, ready-to-edit template from Crixaa.";
    }
}
