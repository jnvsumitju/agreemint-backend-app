package com.agreemint.admin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-org quota override. All numeric columns are nullable — a NULL means
 * "fall back to the system default". {@link #frozen} is a hard stop that
 * bypasses every quota check and returns 402/403 on all API activity.
 */
@Entity
@Table(name = "org_quotas")
public class OrgQuota {

    @Id
    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "api_rpm_override")
    private Integer apiRpmOverride;

    @Column(name = "api_daily_cap")
    private Integer apiDailyCap;

    @Column(name = "pdf_daily_cap")
    private Integer pdfDailyCap;

    @Column(nullable = false)
    private boolean frozen = false;

    @Column(name = "frozen_reason", columnDefinition = "TEXT")
    private String frozenReason;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public Integer getApiRpmOverride() { return apiRpmOverride; }
    public void setApiRpmOverride(Integer v) { this.apiRpmOverride = v; }
    public Integer getApiDailyCap() { return apiDailyCap; }
    public void setApiDailyCap(Integer v) { this.apiDailyCap = v; }
    public Integer getPdfDailyCap() { return pdfDailyCap; }
    public void setPdfDailyCap(Integer v) { this.pdfDailyCap = v; }
    public boolean isFrozen() { return frozen; }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }
    public String getFrozenReason() { return frozenReason; }
    public void setFrozenReason(String frozenReason) { this.frozenReason = frozenReason; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
