package com.agreemint.admin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * System-wide feature flag. {@link #defaultEnabled} is the fallback;
 * per-org deviations live in {@link FeatureFlagOverride}.
 */
@Entity
@Table(name = "feature_flags")
public class FeatureFlag {

    @Id
    @Column(length = 64)
    private String key;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "default_enabled", nullable = false)
    private boolean defaultEnabled = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isDefaultEnabled() { return defaultEnabled; }
    public void setDefaultEnabled(boolean defaultEnabled) { this.defaultEnabled = defaultEnabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
