-- Tamper-evidence for generated PDFs: a digest of exactly the bytes we handed
-- out, recorded at generation time.
--
-- Verification is then a lookup: hash the file you are holding, ask whether we
-- issued something with that digest. Any modification — a changed figure, a
-- swapped page, an edited name — produces a different SHA-256 and fails to
-- match, including changes that look identical on screen.
--
-- ── Why a separate table and not a column on generated_documents ────────────
--
-- Because the receipt has to outlive the document. Two things delete a
-- generated_documents row, and neither should destroy the ability to check a
-- PDF that somebody still holds:
--
--   1. The expiry feature (V11, V24) removes documents after a retention
--      period. A customer's contract from two years ago is exactly the file
--      most likely to be questioned, and it is the one whose row is gone.
--   2. Templates cascade. A workspace that deletes a template would otherwise
--      silently invalidate every document ever generated from it.
--
-- So there is no foreign key to generated_documents. document_id is kept as
-- provenance — it resolves while the document exists and is a dangling
-- reference afterwards, which is the correct semantics: the receipt asserts
-- "we issued these bytes on this date", and that stays true forever.
--
-- template_id, version_id and org_id are denormalised for the same reason.
-- Reading them requires no join, and they survive the row they came from.
--
-- ── Why sha256 is indexed but not unique ────────────────────────────────────
--
-- A collision would mean two byte-identical PDFs, which is harmless to report
-- ("yes, we issued this") — but a UNIQUE constraint would turn it into a failed
-- generation for a paying customer. In practice it cannot happen: iText writes
-- a random trailer /ID and a wall-clock creation date into every render, so two
-- documents are never byte-identical even from the same template and data.
-- Trading a real availability risk for a theoretical integrity gain is the
-- wrong way round, so this is a plain index.

CREATE TABLE document_receipts (
    id           UUID PRIMARY KEY,
    document_id  UUID        NOT NULL,
    org_id       UUID,
    template_id  UUID,
    version_id   UUID,
    -- Lowercase hex SHA-256 of the stored PDF. Fixed width by construction.
    sha256       CHAR(64)    NOT NULL,
    -- Size of the bytes that were hashed. Cheap, and it lets a verifier be told
    -- "that is not even the right length" before any digest comparison.
    byte_size    BIGINT      NOT NULL,
    issued_at    TIMESTAMPTZ NOT NULL
);

-- The verification lookup. Every public verify request is exactly this query.
CREATE INDEX idx_document_receipts_sha256 ON document_receipts (sha256);

-- Resolving a document back to its receipt, for the console detail page.
CREATE INDEX idx_document_receipts_document ON document_receipts (document_id);
