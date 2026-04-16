-- V12: Email-based org invitations for unregistered users
CREATE TABLE org_invitations (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email VARCHAR(320) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'VIEWER',
    token VARCHAR(128) NOT NULL UNIQUE,
    invited_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ
);

CREATE INDEX idx_org_invitations_email ON org_invitations(email);
CREATE INDEX idx_org_invitations_token ON org_invitations(token);
CREATE UNIQUE INDEX idx_org_invitations_pending ON org_invitations(org_id, email) WHERE accepted_at IS NULL;
