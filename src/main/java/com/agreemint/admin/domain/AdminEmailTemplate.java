package com.agreemint.admin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-editable override for a system email template (invites, password
 * resets, expiry warnings, …). If no row exists for a given key the
 * rendering layer falls back to the baked-in Thymeleaf template.
 */
@Entity
@Table(name = "admin_email_templates")
public class AdminEmailTemplate {
    // A staff override of a bundled email.
    //
    // The table starts EMPTY — nothing seeds it. A row here replaces the
    // classpath template for that key at render time; deleting the row restores
    // the bundled one.
    //
    // Both columns are Thymeleaf, and both use ${...} — never {{var}}. The body
    // renders in HTML mode, the subject in TEXT mode so interpolated values are
    // not HTML-escaped on their way to the inbox.
    //
    // A BLANK subject means "keep the subject the send path composed". That is
    // usually what you want: those are built per send and carry live values —
    // the OTP code, the document title — which a static override cannot express.


    @Id
    @Column(length = 64)
    private String key;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(name = "body_html", nullable = false, columnDefinition = "TEXT")
    private String bodyHtml;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBodyHtml() { return bodyHtml; }
    public void setBodyHtml(String bodyHtml) { this.bodyHtml = bodyHtml; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
