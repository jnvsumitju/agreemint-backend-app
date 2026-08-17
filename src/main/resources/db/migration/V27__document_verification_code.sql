-- A short, human-transcribable code for each issued document.
--
-- The digest in V26 answers "is this file unaltered", but it needs the file.
-- Somebody holding a *printout* has no bytes to hash, and that is exactly the
-- situation a certificate or an invoice ends up in. The code is what they can
-- read off the page and type in.
--
-- ── Why random and stored, rather than derived from the document id ─────────
--
-- Deriving it (say, a truncated hash of the UUID) would mean anyone who ever
-- saw a document id could compute its code. Ids are not treated as secrets —
-- they appear in webhook payloads and file URLs — so a derivation would quietly
-- turn every leaked id into a working lookup key. A random code has no such
-- relationship: knowing one tells you nothing about any other, and knowing an
-- id tells you nothing at all.
--
-- 75 bits, rendered as 15 Crockford base32 characters in three groups of five
-- (`8FK2M-9QTX4-M7PWR`). Crockford excludes I, L, O and U, so the alphabet has
-- no character pairs that are confusable when read off paper or spoken aloud.
--
-- UNIQUE here, unlike sha256 in V26, and the difference is deliberate: two
-- documents sharing a digest is a legitimate state (byte-identical files) that
-- must not fail a generation, whereas two sharing a code is an ambiguous
-- lookup that must never be allowed to exist. At 75 bits a collision across a
-- billion documents sits below one in a million.

ALTER TABLE document_receipts
    ADD COLUMN verification_code VARCHAR(17);

-- Partial: rows written before this migration have no code, and several NULLs
-- would otherwise collide under a plain unique index.
CREATE UNIQUE INDEX idx_document_receipts_code
    ON document_receipts (verification_code)
    WHERE verification_code IS NOT NULL;
