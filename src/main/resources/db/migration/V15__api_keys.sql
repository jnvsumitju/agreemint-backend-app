-- V15: API keys — org-scoped, fine-grained-scope, optionally-expiring secrets
-- used by customer backends / CI pipelines to call /api/v1/* programmatically.
--
-- Storage model:
--   * key_hash is sha256(raw_key) in hex. The raw key is shown exactly once at
--     creation; the server never stores or logs it afterwards.
--   * key_prefix ("ak_live") and key_last4 (last four chars of the raw key)
--     give a friendly way for users to identify a key in the Developer tab
--     without ever exposing the full value.
--   * scopes is a comma-separated list of names (see ApiKeyScope.java).
--   * revoked_at soft-deletes; rotated_to_id tracks a successor key created by
--     the rotation flow (old key keeps running until expires_at for grace).

CREATE TABLE api_keys (
    id             UUID PRIMARY KEY,
    org_id         UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    created_by     UUID NOT NULL REFERENCES users(id),
    name           VARCHAR(128) NOT NULL,
    key_hash       VARCHAR(64)  NOT NULL UNIQUE,
    key_prefix     VARCHAR(16)  NOT NULL,
    key_last4      VARCHAR(8)   NOT NULL,
    scopes         VARCHAR(512) NOT NULL,
    allowed_ips    VARCHAR(1024),
    rate_limit_rpm INT NOT NULL DEFAULT 120,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ,
    last_used_at   TIMESTAMPTZ,
    last_used_ip   VARCHAR(64),
    revoked_at     TIMESTAMPTZ,
    rotated_to_id  UUID REFERENCES api_keys(id)
);

CREATE INDEX idx_api_keys_org
    ON api_keys(org_id)
    WHERE revoked_at IS NULL;

CREATE INDEX idx_api_keys_hash_active
    ON api_keys(key_hash)
    WHERE revoked_at IS NULL;
