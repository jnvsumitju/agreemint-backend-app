-- Template lifecycle: DRAFT / ACTIVE / ARCHIVED.
--
-- Set by an author, unlike version state, which is derived. Every template has
-- had a committed v1 since creation (TemplateService.create seeds one), so
-- "has a version" never told anyone whether a template was ready to use.
--
-- ACTIVE is a precondition for generating documents, enforced in the service —
-- which makes the backfill below the load-bearing line in this migration.

ALTER TABLE templates
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'DRAFT';

-- Existing templates are ACTIVE, not DRAFT.
--
-- New templates start DRAFT so an unfinished layout cannot be generated from.
-- Applying that default to rows that already exist would be a different thing
-- entirely: every template in every workspace would stop generating the moment
-- this migration ran, including any wired into a customer's live integration.
-- The column default governs new rows; this statement governs the ones already
-- in use, and they need opposite answers.
UPDATE templates SET status = 'ACTIVE';

-- Listing filters on (org_id, status) once archived templates are hidden by
-- default, which is the common read.
CREATE INDEX IF NOT EXISTS idx_templates_org_status ON templates (org_id, status);
