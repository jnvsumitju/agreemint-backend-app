-- =============================================
-- V10: Add optimistic locking version columns
-- =============================================

ALTER TABLE templates ADD COLUMN version BIGINT DEFAULT 0;
ALTER TABLE organizations ADD COLUMN version BIGINT DEFAULT 0;
ALTER TABLE template_drafts ADD COLUMN version BIGINT DEFAULT 0;
