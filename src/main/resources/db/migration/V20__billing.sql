-- Razorpay-backed subscription billing.
--
-- We keep our own record of the subscription rather than calling Razorpay on
-- every page load: the org's entitlement has to be readable cheaply and has to
-- survive Razorpay being unreachable. Razorpay remains the source of truth for
-- money; this table is the source of truth for what the app lets you do.

CREATE TABLE subscriptions (
    id                      UUID PRIMARY KEY,
    org_id                  UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,

    -- Razorpay identifiers. subscription_id is unique so webhook replays and
    -- concurrent deliveries cannot create duplicates.
    razorpay_subscription_id VARCHAR(64) NOT NULL UNIQUE,
    razorpay_plan_id         VARCHAR(64) NOT NULL,
    razorpay_customer_id     VARCHAR(64),

    -- Mirrors Razorpay's subscription status vocabulary:
    -- created, authenticated, active, pending, halted, cancelled, completed, expired
    status                  VARCHAR(32) NOT NULL,

    plan                    VARCHAR(32) NOT NULL,   -- OrgPlan the subscription grants
    billing_period          VARCHAR(16) NOT NULL,   -- MONTHLY | YEARLY

    current_period_end      TIMESTAMPTZ,            -- access is retained until this instant
    cancel_at_period_end    BOOLEAN NOT NULL DEFAULT FALSE,
    cancelled_at            TIMESTAMPTZ,

    created_by              UUID REFERENCES users(id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- An org can accumulate historical subscriptions; only one may be live at a
-- time. Partial unique index enforces that without blocking cancelled rows.
CREATE UNIQUE INDEX ux_subscriptions_active_org
    ON subscriptions (org_id)
    WHERE status IN ('created', 'authenticated', 'active', 'pending', 'halted');

CREATE INDEX ix_subscriptions_org ON subscriptions (org_id);

-- Every webhook Razorpay sends us, recorded before it is acted on.
--
-- razorpay_event_id is unique: Razorpay retries deliveries, and charging or
-- downgrading an org twice for one event would be a real bug. Insert-then-act
-- makes replay handling a constraint violation rather than a race.
CREATE TABLE billing_events (
    id                  UUID PRIMARY KEY,
    razorpay_event_id   VARCHAR(64) NOT NULL UNIQUE,
    event_type          VARCHAR(64) NOT NULL,
    org_id              UUID REFERENCES organizations(id) ON DELETE SET NULL,
    subscription_id     UUID REFERENCES subscriptions(id) ON DELETE SET NULL,

    -- Money, in the smallest currency unit (paise), exactly as Razorpay sends it.
    amount              BIGINT,
    currency            VARCHAR(8),

    razorpay_payment_id VARCHAR(64),
    payload             TEXT NOT NULL,   -- raw JSON, for support and disputes
    processed_at        TIMESTAMPTZ,
    error               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_billing_events_org ON billing_events (org_id, created_at DESC);
CREATE INDEX ix_billing_events_sub ON billing_events (subscription_id, created_at DESC);
