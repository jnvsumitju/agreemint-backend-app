-- Ahead-of-date expiry warnings for generated documents.
--
-- `generated_documents.expires_at` and its partial index already exist from
-- V11__document_lifecycle.sql, along with an hourly job that flips a past-due
-- document to EXPIRED. What was missing is the half the customer actually
-- notices: a warning *before* the date, while there is still time to act.
--
-- Sending that warning needs durable "already told them" state. Without it the
-- sweep re-emails the same document on every run, and because every @Scheduled
-- job runs on every instance (there is no ShedLock in this application), a
-- two-instance deploy would double every send.
--
-- Modelled on api_access_grace.warned_at (V23) — same problem, same shape.

-- expiry_warned_at: when the ahead-of-date warning was sent; NULL means not yet
-- warned. Reset to NULL whenever expires_at changes (see
-- DocumentLifecycleService.setExpiry), so a re-dated document is warned again.
--
-- Documented in a leading comment rather than COMMENT ON COLUMN: no migration
-- in this project uses that statement, Flyway is disabled for the test suite
-- (src/test/resources/application.yml — H2 cannot parse the Postgres-specific
-- SQL here), and there is therefore nothing that would catch a syntax mistake
-- before deploy. Partial indexes below are already proven by V11 and V19.
ALTER TABLE generated_documents
    ADD COLUMN expiry_warned_at TIMESTAMPTZ;

-- The sweep looks for documents that have a date and have not been warned. The
-- existing idx_gendocs_expires (V11) does not carry the expiry_warned_at
-- predicate, so it cannot serve this query without a filter step.
CREATE INDEX idx_gendocs_expiry_warn
    ON generated_documents (expires_at)
    WHERE expires_at IS NOT NULL AND expiry_warned_at IS NULL;
