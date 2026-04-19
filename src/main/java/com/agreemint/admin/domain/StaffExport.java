package com.agreemint.admin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Staff-initiated export job (GDPR dump, audit export, …). Rows are picked
 * up by a scheduled worker that writes the result to S3 and stamps
 * {@link #fileUrl} / {@link #completedAt}.
 */
@Entity
@Table(name = "staff_exports")
public class StaffExport {

    public enum Status { PENDING, PROCESSING, READY, FAILED }

    @Id
    private UUID id;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(nullable = false, length = 32)
    private String scope;       // "org" | "user" | "audit"

    @Column(name = "target_id")
    private UUID targetId;

    @Column(nullable = false, length = 32)
    private String status = Status.PENDING.name();

    @Column(name = "file_url", length = 2048)
    private String fileUrl;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getRequestedBy() { return requestedBy; }
    public void setRequestedBy(UUID requestedBy) { this.requestedBy = requestedBy; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public UUID getTargetId() { return targetId; }
    public void setTargetId(UUID targetId) { this.targetId = targetId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
