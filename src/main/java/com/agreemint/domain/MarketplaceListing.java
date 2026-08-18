package com.agreemint.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "marketplace_listings")
public class MarketplaceListing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "author_id")
    private UUID authorId;

    @Column(name = "author_name", length = 256)
    private String authorName;

    @Column(name = "org_id")
    private UUID orgId;

    /**
     * The template this listing was published from.
     *
     * <p>Provenance only. Since V25 the content lives on the listing itself, so
     * this is never dereferenced to install — a deleted source template no
     * longer breaks the listing.
     */
    @Column(name = "source_template_id")
    private UUID sourceTemplateId;

    /** The version that was snapshotted, for support questions. */
    @Column(name = "source_version_id")
    private UUID sourceVersionId;

    /**
     * The layout, copied at publish time.
     *
     * <p>Snapshotting rather than live-linking is what makes a listing a stable
     * artifact: the publisher can keep editing their template without silently
     * changing what everyone who already installed it agreed to.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layout_json")
    private JsonNode layoutJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private JsonNode variables;

    @Column(name = "thumbnail_url", length = 1024)
    private String thumbnailUrl;

    @Column(length = 64)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String tags;

    @Column(name = "install_count", nullable = false)
    private int installCount = 0;

    @Column(nullable = false)
    private boolean published = false;

    /**
     * First-party listing, published from the Crixaa org.
     *
     * <p>Drives the "from Crixaa" badge and ordering in the console, and lets a
     * FREE-plan org browse and install this row while the rest of the
     * marketplace stays a Starter+ feature. Ownership is otherwise identical to
     * any other listing — these are published by a real org through the normal
     * path, so nothing downstream needs to special-case them.
     */
    @Column(nullable = false)
    private boolean official = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public boolean isOfficial() { return official; }
    public void setOfficial(boolean official) { this.official = official; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public UUID getAuthorId() { return authorId; }
    public void setAuthorId(UUID authorId) { this.authorId = authorId; }

    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }

    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }

    public UUID getSourceTemplateId() { return sourceTemplateId; }
    public void setSourceTemplateId(UUID sourceTemplateId) { this.sourceTemplateId = sourceTemplateId; }

    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public int getInstallCount() { return installCount; }
    public void setInstallCount(int installCount) { this.installCount = installCount; }

    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public UUID getSourceVersionId() { return sourceVersionId; }
    public void setSourceVersionId(UUID sourceVersionId) { this.sourceVersionId = sourceVersionId; }

    public JsonNode getLayoutJson() { return layoutJson; }
    public void setLayoutJson(JsonNode layoutJson) { this.layoutJson = layoutJson; }

    public JsonNode getVariables() { return variables; }
    public void setVariables(JsonNode variables) { this.variables = variables; }
}
