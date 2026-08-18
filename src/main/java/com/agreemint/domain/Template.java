package com.agreemint.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "templates")
/*
 * Only the changed columns are written on update.
 *
 * Without this Hibernate emits a static all-column UPDATE from the snapshot it
 * read at the start of the transaction, and the thumbnail columns are written
 * out of band by bulk UPDATEs on a background thread that deliberately do not
 * bump @Version. Any concurrent save — a status change, a rename — would
 * therefore flush its stale copy of those columns back over a freshly rendered
 * thumbnail, pass the version check, and silently lose the write.
 */
@org.hibernate.annotations.DynamicUpdate
public class Template {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 512)
    private String name;

    @Column(name = "created_by", length = 256)
    private String createdBy;

    @Column(name = "org_id")
    private UUID orgId;

    /**
     * Lifecycle state. New templates start {@link TemplateStatus#DRAFT}; the
     * V30 migration backfilled every pre-existing row to ACTIVE so nothing in
     * use stopped generating.
     */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TemplateStatus status = TemplateStatus.DRAFT;

    /** R2 key of the in-progress preview, refreshed while someone edits. */
    @Column(name = "draft_thumbnail_key", length = 512)
    private String draftThumbnailKey;

    /** R2 key of the last committed version's preview. */
    @Column(name = "thumbnail_key", length = 512)
    private String thumbnailKey;

    /** When a thumbnail was last rendered — the 60-second capture checks this. */
    @Column(name = "thumbnail_updated_at")
    private Instant thumbnailUpdatedAt;

    /**
     * Slug this template is published under on crixaa.com, e.g.
     * {@code free-gst-invoice-template}. Null for every customer template.
     *
     * <p>Doubles as the switch for public thumbnails: only a template with a
     * slug has a page to appear on, so only those are mirrored into the
     * world-readable bucket. See {@code TemplateThumbnailService}.
     */
    @Column(name = "public_slug", length = 160)
    private String publicSlug;

    @Column(name = "owner_id")
    private UUID ownerId;

    /**
     * FK to {@code products.id}. Nullable for legacy templates created
     * before the Products feature existed; new templates are required to
     * set one via the create path.
     */
    @Column(name = "product_id")
    private UUID productId;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getDraftThumbnailKey() { return draftThumbnailKey; }
    public void setDraftThumbnailKey(String v) { this.draftThumbnailKey = v; }

    public String getThumbnailKey() { return thumbnailKey; }
    public void setThumbnailKey(String v) { this.thumbnailKey = v; }

    public Instant getThumbnailUpdatedAt() { return thumbnailUpdatedAt; }
    public void setThumbnailUpdatedAt(Instant v) { this.thumbnailUpdatedAt = v; }

    public String getPublicSlug() { return publicSlug; }
    public void setPublicSlug(String v) { this.publicSlug = v; }

    public TemplateStatus getStatus() {
        return status;
    }

    public void setStatus(TemplateStatus status) {
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(UUID ownerId) {
        this.ownerId = ownerId;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }
}
