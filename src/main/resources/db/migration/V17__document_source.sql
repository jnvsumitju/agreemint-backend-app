-- V17: Tag each generated document with how it was created.
--
-- Documents produced by the in-app editor ("UI_GENERATED") keep the full
-- lifecycle / review workflow. Documents produced via the public developer
-- API ("API_GENERATED") skip the lifecycle entirely — the consuming company
-- runs their own review/approval system on their side and our tracking is
-- redundant.
--
-- Existing rows were all UI-generated, so the default backfills correctly.

ALTER TABLE generated_documents
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'UI_GENERATED';

CREATE INDEX idx_gendocs_source ON generated_documents(org_id, source);
