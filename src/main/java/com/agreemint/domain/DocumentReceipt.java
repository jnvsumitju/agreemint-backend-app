package com.agreemint.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Proof that a specific sequence of bytes was issued by this platform.
 *
 * <p>Written once, at generation, over exactly the PDF that reaches the caller.
 * Never updated. Verification is a lookup by {@link #sha256}: any modification
 * to the file produces a different digest and finds nothing.
 *
 * <p><b>Deliberately not a column on {@code GeneratedDocument}, and holding no
 * foreign key to it.</b> A receipt has to outlive the document it describes —
 * documents are deleted by the expiry feature and cascade away with their
 * template, and neither of those should make a PDF that somebody is still
 * holding unverifiable. {@link #documentId} is provenance, not a relationship;
 * it resolves while the document exists and dangles afterwards, which is the
 * intended semantics. The same reasoning is why template, version and org ids
 * are copied here rather than reached through a join.
 *
 * @see com.agreemint.service.DocumentReceiptService
 */
@Entity
@Table(name = "document_receipts")
public class DocumentReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "template_id")
    private UUID templateId;

    @Column(name = "version_id")
    private UUID versionId;

    /** Lowercase hex SHA-256 of the stored PDF. */
    @Column(nullable = false, length = 64)
    private String sha256;

    /**
     * Short printable code, formatted {@code 8FK2M-9QTX4-M7PWR}. Random rather
     * than derived — see {@code VerificationCodes}. Null for receipts written
     * before V27.
     */
    @Column(name = "verification_code", length = 17)
    private String verificationCode;

    /** Length of the bytes that were hashed. */
    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    public UUID getId() { return id; }

    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }

    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }

    public UUID getTemplateId() { return templateId; }
    public void setTemplateId(UUID templateId) { this.templateId = templateId; }

    public UUID getVersionId() { return versionId; }
    public void setVersionId(UUID versionId) { this.versionId = versionId; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public String getVerificationCode() { return verificationCode; }
    public void setVerificationCode(String verificationCode) { this.verificationCode = verificationCode; }

    public long getByteSize() { return byteSize; }
    public void setByteSize(long byteSize) { this.byteSize = byteSize; }

    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }
}
