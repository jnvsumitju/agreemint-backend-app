package com.agreemint.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_lifecycle_events")
public class DocumentLifecycleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private GeneratedDocument document;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_name", length = 256)
    private String actorName;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private LifecycleStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private LifecycleStatus toStatus;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(columnDefinition = "TEXT")
    private String comment;

    // Column is JSONB; the field is a pre-serialised JSON string. Same
    // `?::jsonb` cast as ActivityLog — see the note there.
    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public GeneratedDocument getDocument() {
        return document;
    }

    public void setDocument(GeneratedDocument document) {
        this.document = document;
    }

    public UUID getActorId() {
        return actorId;
    }

    public void setActorId(UUID actorId) {
        this.actorId = actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public void setActorName(String actorName) {
        this.actorName = actorName;
    }

    public LifecycleStatus getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(LifecycleStatus fromStatus) {
        this.fromStatus = fromStatus;
    }

    public LifecycleStatus getToStatus() {
        return toStatus;
    }

    public void setToStatus(LifecycleStatus toStatus) {
        this.toStatus = toStatus;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
