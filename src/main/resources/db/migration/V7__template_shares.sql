-- V7: Template sharing (link-based + role-based per-user access)
CREATE TABLE template_shares (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES templates (id) ON DELETE CASCADE,
    shared_with_user_id UUID REFERENCES users (id) ON DELETE CASCADE,
    shared_with_email VARCHAR(320),
    role VARCHAR(32) NOT NULL DEFAULT 'VIEWER',
    share_token VARCHAR(128),
    expires_at TIMESTAMPTZ,
    created_by UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_template_share_token UNIQUE (share_token)
);

CREATE INDEX idx_template_shares_template ON template_shares (template_id);
CREATE INDEX idx_template_shares_user ON template_shares (shared_with_user_id);
CREATE INDEX idx_template_shares_token ON template_shares (share_token);
