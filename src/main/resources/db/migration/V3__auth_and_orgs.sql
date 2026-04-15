-- =============================================
-- V3: Authentication & Multi-tenant Organizations
-- =============================================

-- Users
CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    name VARCHAR(256) NOT NULL,
    avatar_url TEXT,
    password_hash VARCHAR(256),
    provider VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
    provider_id VARCHAR(256),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE INDEX idx_users_provider ON users (provider, provider_id);

-- Organizations
CREATE TABLE organizations (
    id UUID PRIMARY KEY,
    name VARCHAR(256) NOT NULL,
    slug VARCHAR(128) NOT NULL,
    logo_url TEXT,
    plan VARCHAR(32) NOT NULL DEFAULT 'FREE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_organizations_slug UNIQUE (slug)
);

-- Organization memberships (user <-> org with role)
CREATE TABLE org_memberships (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    org_id UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL DEFAULT 'VIEWER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_org_membership UNIQUE (user_id, org_id)
);

CREATE INDEX idx_org_memberships_user ON org_memberships (user_id);
CREATE INDEX idx_org_memberships_org ON org_memberships (org_id);

-- Link templates to orgs and owners (nullable for backward compat)
ALTER TABLE templates ADD COLUMN org_id UUID REFERENCES organizations (id) ON DELETE SET NULL;
ALTER TABLE templates ADD COLUMN owner_id UUID REFERENCES users (id) ON DELETE SET NULL;

CREATE INDEX idx_templates_org ON templates (org_id);
CREATE INDEX idx_templates_owner ON templates (owner_id);

-- Refresh tokens for JWT rotation
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(256) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

-- Password reset tokens
CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(256) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_password_reset_tokens_user ON password_reset_tokens (user_id);
