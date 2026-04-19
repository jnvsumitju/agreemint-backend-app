package com.agreemint.admin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-org override for a {@link FeatureFlag}. Composite PK (flag_key, org_id).
 */
@Entity
@Table(name = "feature_flag_overrides")
@IdClass(FeatureFlagOverride.PK.class)
public class FeatureFlagOverride {

    @Id
    @Column(name = "flag_key", length = 64)
    private String flagKey;

    @Id
    @Column(name = "org_id")
    private UUID orgId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public String getFlagKey() { return flagKey; }
    public void setFlagKey(String flagKey) { this.flagKey = flagKey; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public static class PK implements Serializable {
        private String flagKey;
        private UUID orgId;

        public PK() {}
        public PK(String flagKey, UUID orgId) {
            this.flagKey = flagKey;
            this.orgId = orgId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(flagKey, pk.flagKey) && Objects.equals(orgId, pk.orgId);
        }

        @Override
        public int hashCode() { return Objects.hash(flagKey, orgId); }
    }
}
