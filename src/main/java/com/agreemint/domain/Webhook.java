package com.agreemint.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A customer-managed webhook subscription: "POST these events to this URL,
 * signed with this HMAC secret".
 *
 * <p>The raw secret is shown to the customer exactly once on creation; the
 * server also retains it (in the {@link #secret} column) because the
 * dispatcher needs it at delivery time to compute the
 * {@code X-Agreemint-Signature} header. Treat rows as sensitive.
 */
@Entity
@Table(name = "webhooks")
public class Webhook {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(nullable = false, length = 128)
    private String secret;

    @Column(name = "secret_last4", nullable = false, length = 8)
    private String secretLast4;

    @Column(nullable = false, length = 1024)
    private String events;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public Set<String> eventSet() {
        if (events == null || events.isBlank()) return Set.of();
        return Arrays.stream(events.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public boolean isLive() {
        return active && revokedAt == null;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public String getSecretLast4() { return secretLast4; }
    public void setSecretLast4(String secretLast4) { this.secretLast4 = secretLast4; }
    public String getEvents() { return events; }
    public void setEvents(String events) { this.events = events; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
