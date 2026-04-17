-- V13: Template review workflow.
--
-- A "review" is a request from a designer/admin (requester) to another user
-- (reviewer) to sign off on a specific committed template version. Reviewers
-- can APPROVE or request mandatory changes (CHANGES_REQUESTED); while any
-- CHANGES_REQUESTED review exists against the latest committed version of a
-- template, the next commit is blocked (see TemplateDraftService).
--
-- One row per (version_id, reviewer_id). Re-requesting review on the same
-- version for the same reviewer flips status back to PENDING rather than
-- inserting a duplicate (enforced by the unique index below + service logic).

CREATE TABLE template_reviews (
    id           UUID PRIMARY KEY,
    template_id  UUID NOT NULL REFERENCES templates(id) ON DELETE CASCADE,
    version_id   UUID NOT NULL REFERENCES template_versions(id) ON DELETE CASCADE,
    requester_id UUID NOT NULL REFERENCES users(id),
    reviewer_id  UUID NOT NULL REFERENCES users(id),
    status       VARCHAR(32) NOT NULL,   -- PENDING | APPROVED | CHANGES_REQUESTED | DISMISSED
    message      TEXT,                    -- requester's note when asking for review
    summary      TEXT,                    -- reviewer's note on decision
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at   TIMESTAMPTZ
);

CREATE UNIQUE INDEX ux_template_reviews_version_reviewer
    ON template_reviews (version_id, reviewer_id);

CREATE INDEX idx_template_reviews_template
    ON template_reviews (template_id);

CREATE INDEX idx_template_reviews_reviewer_status
    ON template_reviews (reviewer_id, status);
