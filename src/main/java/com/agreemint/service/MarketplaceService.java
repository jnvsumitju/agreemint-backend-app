package com.agreemint.service;

import com.agreemint.api.BadRequestException;
import com.agreemint.api.NotFoundException;
import com.agreemint.api.dto.MarketplaceListingResponse;
import com.agreemint.domain.MarketplaceListing;
import com.agreemint.domain.Product;
import com.agreemint.domain.Template;
import com.agreemint.domain.TemplateVersion;
import com.agreemint.repository.MarketplaceListingRepository;
import com.agreemint.repository.ProductRepository;
import com.agreemint.repository.TemplateRepository;
import com.agreemint.repository.TemplateVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class MarketplaceService {

    private final MarketplaceListingRepository listingRepo;
    private final TemplateRepository templateRepo;
    private final TemplateVersionRepository versionRepo;
    private final ProductRepository productRepo;

    public MarketplaceService(MarketplaceListingRepository listingRepo,
                              TemplateRepository templateRepo,
                              TemplateVersionRepository versionRepo,
                              ProductRepository productRepo) {
        this.listingRepo = listingRepo;
        this.templateRepo = templateRepo;
        this.versionRepo = versionRepo;
        this.productRepo = productRepo;
    }

    @Transactional(readOnly = true)
    public List<MarketplaceListingResponse> listPublished() {
        return listingRepo.findByPublishedTrueOrderByCreatedAtDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MarketplaceListingResponse> listByCategory(String category) {
        return listingRepo.findByCategoryAndPublishedTrue(category)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public MarketplaceListingResponse getById(UUID id) {
        MarketplaceListing listing = listingRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Marketplace listing not found"));
        // Withdrawal has to remove the listing from direct lookup too. A title
        // and description can name a real client on their own, so leaving them
        // readable to anyone who noted the id defeats the point of withdrawing.
        if (!listing.isPublished()) {
            throw new NotFoundException("Marketplace listing not found");
        }
        return toResponse(listing);
    }

    /**
     * Publish a template the caller owns.
     *
     * <p>The listing's {@code orgId} is taken from the <em>template</em>, not
     * from the caller's current org context. Those can differ for someone who
     * belongs to more than one workspace, and attributing a listing to the
     * wrong workspace is the sort of error nobody notices until an author
     * cannot find their own listing.
     *
     * <p><strong>The caller's right to publish this template is not checked
     * here.</strong> It is asserted in {@code MarketplaceController} via
     * {@code assertTemplateAccess}, matching how every other template write in
     * this codebase is gated. Do not call this method from a new path without
     * performing that check first: {@code sourceTemplateId} arrives from the
     * request body, and without it any Starter user could publish another
     * workspace's template and then clone it back out.
     */
    @Transactional
    public MarketplaceListingResponse publish(UUID authorId, String authorName,
                                               UUID sourceTemplateId, String title,
                                               String description, String category, String tags) {
        Template source = templateRepo.findById(sourceTemplateId)
                .orElseThrow(() -> new NotFoundException("Template not found"));

        // A listing must belong to a workspace, because withdrawal is authorized
        // against the owning org. Templates can legitimately have no org —
        // V14__backfill_template_org.sql leaves one orphaned when its creator
        // has no membership, and assertTemplateAccess deliberately lets the
        // owner through on ownerId alone — so this is reachable, not defensive
        // padding. Publishing one would produce a permanently public listing
        // that ownerOrgId() resolves to null for, which means nobody, including
        // its author, could ever withdraw it.
        if (source.getOrgId() == null) {
            throw new BadRequestException(
                    "This template does not belong to a workspace, so it cannot be published. "
                            + "Move it into a workspace first.");
        }

        // Publishing the same template twice creates two identical live
        // listings, each needing its own withdrawal. That is easy to do by
        // accident: the console gave no success signal for a while, so an unsure
        // author would simply click Publish again. Re-publishing after a
        // withdrawal is still allowed — only a currently-live duplicate is
        // refused.
        if (listingRepo.existsBySourceTemplateIdAndPublishedTrue(sourceTemplateId)) {
            throw new BadRequestException(
                    "This template is already listed in the marketplace. Withdraw the existing "
                            + "listing first if you want to republish it.");
        }

        // Snapshot the content onto the listing. A listing is a stable artifact
        // from this point: later edits to the source template do not change what
        // anyone installs, and deleting the source does not break it.
        TemplateVersion latest = versionRepo.findFirstByTemplateOrderByVersionNumberDesc(source)
                .orElseThrow(() -> new BadRequestException(
                        "Commit a version of this template before publishing it"));

        MarketplaceListing listing = new MarketplaceListing();
        listing.setType("TEMPLATE");
        listing.setTitle(title);
        listing.setDescription(description);
        listing.setAuthorId(authorId);
        listing.setAuthorName(authorName);
        listing.setOrgId(source.getOrgId());
        listing.setSourceTemplateId(sourceTemplateId);
        listing.setSourceVersionId(latest.getId());
        listing.setLayoutJson(latest.getLayoutJson());
        listing.setVariables(latest.getVariables());
        listing.setCategory(category);
        listing.setTags(tags);
        // Published immediately — there is no moderation queue. That is a
        // deliberate choice, and it is why withdraw() exists: with nothing
        // standing between publish and every other workspace, self-service
        // withdrawal is the only way to take a listing back.
        listing.setPublished(true);
        listingRepo.save(listing);
        return toResponse(listing);
    }

    /**
     * Withdraw a listing from the catalogue.
     *
     * <p>The row is unpublished rather than deleted, so install counts and the
     * provenance of anything already installed survive. Already-installed
     * copies are unaffected — they are independent templates.
     *
     * <p>Callers must have already established that the actor belongs to the
     * listing's org; see {@code MarketplaceController.withdraw}.
     */
    @Transactional
    public void withdraw(UUID listingId) {
        MarketplaceListing listing = listingRepo.findById(listingId)
                .orElseThrow(() -> new NotFoundException("Marketplace listing not found"));
        listing.setPublished(false);
        listing.setUpdatedAt(Instant.now());
        listingRepo.save(listing);
    }

    /** Every listing this org has published, withdrawn ones included. */
    @Transactional(readOnly = true)
    public List<MarketplaceListingResponse> listByOrg(UUID orgId) {
        return listingRepo.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(this::toResponse)
                .toList();
    }

    /** The org that published a listing, for authorization checks. */
    @Transactional(readOnly = true)
    public UUID ownerOrgId(UUID listingId) {
        return listingRepo.findById(listingId)
                .orElseThrow(() -> new NotFoundException("Marketplace listing not found"))
                .getOrgId();
    }

    /**
     * Install a listing into {@code targetOrgId} as a new template.
     *
     * <p>The content comes from the listing's snapshot, taken at publish time.
     * Template content lives in {@code template_versions.layout_json} —
     * {@code templates} holds only a name — so an install that creates the row
     * and stops produces a blank document, which is what this used to do while
     * the console reported success.
     */
    @Transactional
    public Template cloneTemplate(UUID listingId, UUID targetOrgId, UUID ownerId) {
        MarketplaceListing listing = listingRepo.findById(listingId)
                .orElseThrow(() -> new NotFoundException("Marketplace listing not found"));

        // Installed from the listing's own snapshot, never from the live source
        // template. That is the whole point of snapshotting: the source may have
        // been edited or deleted since publish, and neither should change or
        // break what an installer receives.
        // A withdrawn listing must not be installable. Withdrawal is the only
        // recall mechanism — there is no moderation queue — so without this it
        // removes the listing from the catalogue while anyone holding the id
        // can still install the content it was withdrawn to protect.
        if (!listing.isPublished()) {
            throw new NotFoundException("This listing is no longer available");
        }
        if (listing.getLayoutJson() == null) {
            throw new NotFoundException("This listing has no content to install");
        }

        Template clone = new Template();
        clone.setName(listing.getTitle());
        clone.setOrgId(targetOrgId);
        clone.setOwnerId(ownerId);
        clone.setCreatedBy(listing.getAuthorName());
        // templates.product_id is ON DELETE RESTRICT and TemplateService.create
        // treats it as required, so a clone without one is a row the rest of
        // the product refuses to work with.
        clone.setProductId(resolveTargetProductId(targetOrgId));
        templateRepo.save(clone);

        TemplateVersion copy = new TemplateVersion();
        copy.setTemplate(clone);
        copy.setVersionNumber(1);
        copy.setLayoutJson(listing.getLayoutJson());
        copy.setVariables(listing.getVariables());
        versionRepo.save(copy);

        listing.setInstallCount(listing.getInstallCount() + 1);
        listing.setUpdatedAt(Instant.now());
        listingRepo.save(listing);

        return clone;
    }


    /**
     * A product in the installing org to hang the clone off.
     *
     * <p>Products are per-org, so the source's own product id is meaningless
     * here — reusing it would point the clone at another workspace's row.
     * Falls back to creating a "Marketplace" product rather than failing the
     * install, so someone who has not set products up can still install one.
     */
    private UUID resolveTargetProductId(UUID targetOrgId) {
        List<Product> existing = productRepo.findByOrgIdOrderByNameAsc(targetOrgId);
        if (!existing.isEmpty()) return existing.get(0).getId();

        Product created = new Product();
        created.setOrgId(targetOrgId);
        created.setName("Marketplace");
        productRepo.save(created);
        return created.getId();
    }

    private MarketplaceListingResponse toResponse(MarketplaceListing m) {
        return new MarketplaceListingResponse(
                m.getId(),
                m.getType(),
                m.getTitle(),
                m.getDescription(),
                m.getAuthorName(),
                m.getThumbnailUrl(),
                m.getCategory(),
                m.getTags(),
                m.getInstallCount(),
                m.isPublished(),
                m.getCreatedAt()
        );
    }
}
