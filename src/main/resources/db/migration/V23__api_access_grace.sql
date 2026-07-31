-- Grace period between a paid plan lapsing and its API keys being revoked.
--
-- Access does not stop at the moment a subscription ends. The workspace is
-- warned, keeps working for a few days, and only then loses its keys — so an
-- integration never dies on a request the customer had no warning about.
--
-- This needs durable state rather than being derived from the subscription row,
-- for two reasons. The job runs on a schedule and must send each email exactly
-- once, which requires remembering that it already did. And a customer who
-- resubscribes during the grace period must have the pending revocation
-- cancelled, which is a fact about the grace period, not about any subscription.
CREATE TABLE api_access_grace (
    org_id      UUID PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    -- When the paid plan lapsed. The revocation deadline is derived from this.
    lapsed_at   TIMESTAMPTZ NOT NULL,
    -- Set once the "your API access ends in N days" email has gone out.
    warned_at   TIMESTAMPTZ,
    -- Set once the keys were actually revoked. A non-null value means this row
    -- is finished; it is kept as a record rather than deleted.
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The job's only query: rows still awaiting revocation, oldest first.
CREATE INDEX idx_api_access_grace_pending
    ON api_access_grace (lapsed_at) WHERE revoked_at IS NULL;
