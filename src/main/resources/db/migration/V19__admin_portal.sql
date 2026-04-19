-- Admin portal schema — everything the internal staff UI needs to operate
-- across all customer orgs. One migration keeps the admin concerns grouped.

-- Staff flag on users — gates every /api/admin/* route in SecurityConfig and
-- is added to the issued JWT so the frontend can paint the right UI.
ALTER TABLE users
    ADD COLUMN is_staff BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_users_is_staff
    ON users (is_staff) WHERE is_staff = TRUE;

-- System-wide or per-org announcement banners pushed to the main app.
-- `target_org_ids` is NULL for global announcements; otherwise it's a
-- comma-separated list of UUIDs (small set, not worth a join table yet).
CREATE TABLE announcements (
    id              UUID PRIMARY KEY,
    created_by      UUID NOT NULL REFERENCES users(id),
    title           VARCHAR(200) NOT NULL,
    body            TEXT NOT NULL,
    severity        VARCHAR(16) NOT NULL DEFAULT 'info',      -- info | warning | critical
    target_org_ids  VARCHAR(4096),                              -- NULL = global
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    starts_at       TIMESTAMPTZ,
    ends_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_announcements_active ON announcements (active, starts_at, ends_at);

-- Feature flags — the editor reads this table on boot to unlock things.
-- `default_enabled` is the system-wide default; per-org overrides live
-- in feature_flag_overrides so we don't rewrite rows on each toggle.
CREATE TABLE feature_flags (
    key             VARCHAR(64) PRIMARY KEY,
    description     TEXT,
    default_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE feature_flag_overrides (
    flag_key    VARCHAR(64) NOT NULL REFERENCES feature_flags(key) ON DELETE CASCADE,
    org_id      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    enabled     BOOLEAN NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (flag_key, org_id)
);

-- Per-org quota overrides — supersede the defaults in RateLimitConfig.
-- NULL columns fall back to the system default; column-per-quota keeps
-- the overrides self-describing.
CREATE TABLE org_quotas (
    org_id             UUID PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    api_rpm_override   INTEGER,       -- per-key default; NULL = system default
    api_daily_cap      INTEGER,       -- org-wide daily request cap
    pdf_daily_cap      INTEGER,       -- org-wide PDF generations per day
    frozen             BOOLEAN NOT NULL DEFAULT FALSE, -- hard stop across the org
    frozen_reason      TEXT,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by         UUID REFERENCES users(id)
);

-- Staff-initiated export jobs (GDPR-style dumps, audit exports, etc.).
-- Processed async by a scheduled worker (V3; stubbed for now).
CREATE TABLE staff_exports (
    id            UUID PRIMARY KEY,
    requested_by  UUID NOT NULL REFERENCES users(id),
    scope         VARCHAR(32) NOT NULL,              -- 'org' | 'user' | 'audit'
    target_id     UUID,                               -- org or user id, nullable for audit
    status        VARCHAR(32) NOT NULL DEFAULT 'PENDING', -- PENDING | PROCESSING | READY | FAILED
    file_url      VARCHAR(2048),                      -- signed S3 URL once READY
    error         TEXT,
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ
);
CREATE INDEX idx_staff_exports_status ON staff_exports (status) WHERE status IN ('PENDING', 'PROCESSING');

-- Admin-editable system email templates. Seeded from files on first deploy;
-- an override here wins at render time. Schema is intentionally simple —
-- body is Thymeleaf; subject is a plain string with {{var}} placeholders.
CREATE TABLE admin_email_templates (
    key         VARCHAR(64) PRIMARY KEY,  -- 'invite', 'password-reset', …
    subject     VARCHAR(200) NOT NULL,
    body_html   TEXT NOT NULL,
    updated_by  UUID REFERENCES users(id),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
