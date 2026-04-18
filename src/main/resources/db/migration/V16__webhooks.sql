-- V16: Outbound webhooks.
--
-- Two tables:
--   * webhooks          — the subscription (URL + HMAC secret + event list)
--   * webhook_deliveries — per-attempt log with status, retry schedule, response
--
-- Delivery: on each emit the service creates a PENDING delivery row; a
-- scheduled dispatcher picks up PENDING rows whose next_retry_at <= now() and
-- POSTs the payload signed with HMAC-SHA256 in the X-Agreemint-Signature
-- header. Non-2xx responses schedule an exponential retry, capped at 8
-- attempts → ABANDONED.

CREATE TABLE webhooks (
    id           UUID PRIMARY KEY,
    org_id       UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    created_by   UUID NOT NULL REFERENCES users(id),
    url          VARCHAR(2048) NOT NULL,
    -- Raw HMAC secret used to sign outbound bodies. Shown to the customer
    -- exactly once on creation; the dispatcher still needs it at delivery
    -- time to compute the X-Agreemint-Signature header.
    secret       VARCHAR(128)  NOT NULL,
    secret_last4 VARCHAR(8)    NOT NULL,
    events       VARCHAR(1024) NOT NULL,       -- comma-separated event names
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at   TIMESTAMPTZ
);
CREATE INDEX idx_webhooks_org_active
    ON webhooks(org_id)
    WHERE revoked_at IS NULL AND active = TRUE;

CREATE TABLE webhook_deliveries (
    id             UUID PRIMARY KEY,
    webhook_id     UUID NOT NULL REFERENCES webhooks(id) ON DELETE CASCADE,
    event          VARCHAR(64) NOT NULL,
    payload        TEXT NOT NULL,
    attempt        INT NOT NULL DEFAULT 0,
    max_attempts   INT NOT NULL DEFAULT 8,
    status         VARCHAR(32) NOT NULL,      -- PENDING | SUCCEEDED | FAILED | ABANDONED
    response_code  INT,
    response_body  TEXT,                       -- truncated to 2 KB
    error          TEXT,
    next_retry_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at   TIMESTAMPTZ
);
CREATE INDEX idx_webhook_deliveries_pending
    ON webhook_deliveries(status, next_retry_at)
    WHERE status = 'PENDING';
CREATE INDEX idx_webhook_deliveries_webhook
    ON webhook_deliveries(webhook_id, created_at DESC);
