package com.agreemint.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One workspace's grace period between its paid plan lapsing and its API keys
 * being revoked.
 *
 * <p>A row exists only while a lapse is being worked through. {@code revokedAt}
 * non-null means it is finished and kept as a record; the row is deleted
 * outright if the customer resubscribes, because a cancelled grace period is
 * not a thing that happened to them.
 */
@Entity
@Table(name = "api_access_grace")
public class ApiAccessGrace {

    @Id
    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "lapsed_at", nullable = false)
    private Instant lapsedAt = Instant.now();

    /** Set when the warning email goes out, so it goes out exactly once. */
    @Column(name = "warned_at")
    private Instant warnedAt;

    /** Set when the keys are revoked. Non-null means this row is done. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID v) { this.orgId = v; }
    public Instant getLapsedAt() { return lapsedAt; }
    public void setLapsedAt(Instant v) { this.lapsedAt = v; }
    public Instant getWarnedAt() { return warnedAt; }
    public void setWarnedAt(Instant v) { this.warnedAt = v; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant v) { this.revokedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
