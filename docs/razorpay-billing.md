# Razorpay subscription billing

Recurring billing for the PRO plan, using Razorpay's **Subscriptions** API.

## How it fits together

```
Console (Billing tab)                Backend                       Razorpay
  │                                    │                              │
  ├─ POST /billing/subscription ──────►│─ create subscription ───────►│
  │◄──────────── subscription_id ──────┤◄──── subscription_id ────────┤
  │                                    │                              │
  ├─ opens Checkout ──────────────────────────────────────────────────►│
  │◄──── payment_id, signature ────────────────────────────────────────┤
  ├─ POST /subscription/confirm ──────►│  (verify signature only)      │
  │                                    │                              │
  │                                    │◄── webhook: subscription.* ───┤
  │                                    │   org.plan = PRO  ← the real  │
  │                                    │                    entitlement│
```

**Webhooks grant the plan, not the browser.** The checkout callback is verified
and used to move the UI along, but a customer who closes the tab must still end
up on PRO, and a customer who forges a callback must not. Everything that
changes `organizations.plan` happens in the webhook path.

## One-time setup

### 1. Create the Plans

Razorpay Dashboard → **Subscriptions → Plans → Create Plan**. Make one per
billing cycle:

| Plan | Billing cycle | Interval |
|---|---|---|
| Crixaa Pro — Monthly | Monthly | 1 |
| Crixaa Pro — Yearly | Yearly | 1 |

The **amount and currency live on the Plan**, not in our code. Changing a price
means creating a new Plan and swapping the id — existing subscribers stay on the
plan they signed up to, which is the behaviour you want.

Copy each `plan_XXXXXXXXXXXX` id.

### 2. Register the webhook

Dashboard → **Settings → Webhooks → Add New Webhook**.

- **URL:** `https://api.crixaa.com/api/webhooks/razorpay`
- **Secret:** generate a strong random string. This is **not** your API key
  secret — it is a separate value, and mixing them up makes every webhook fail
  signature verification.

Subscribe to exactly these events:

```
subscription.activated
subscription.charged
subscription.pending
subscription.halted
subscription.cancelled
subscription.completed
subscription.updated
subscription.paused
subscription.resumed
```

### 3. Set the environment variables

In `envs/.agreemint.env` on the server:

```bash
RAZORPAY_KEY_ID=rzp_live_xxxxxxxxxxxx
RAZORPAY_KEY_SECRET=your_key_secret
RAZORPAY_WEBHOOK_SECRET=the_secret_you_set_in_step_2
RAZORPAY_PLAN_PRO_MONTHLY=plan_xxxxxxxxxxxx
RAZORPAY_PLAN_PRO_YEARLY=plan_yyyyyyyyyyyy
```

Then `make restart`.

`RAZORPAY_KEY_ID` is public — the browser needs it to open Checkout. The other
three must never leave the server.

### 4. Test before going live

You have a live account, so **use test keys first** (`rzp_test_...`, with Plans
created in test mode). A mistake in live mode moves real money.

Razorpay's test cards are in their docs; `4111 1111 1111 1111` with any future
expiry and any CVV is the usual one.

## Plan limits — read this before setting them

`agreemint.plans.*` controls per-plan ceilings. **Every value is unset by
default and that is deliberate.**

Before this feature, no org had a plan-derived cap: everyone fell back to
`agreemint.ratelimit.org-daily-max` (10,000/day). Setting `PLAN_FREE_API_DAILY_MAX`
throttles every existing free org the moment it deploys.

Decide the commercial limits first, then set:

```bash
PLAN_FREE_API_DAILY_MAX=500
PLAN_PRO_API_DAILY_MAX=10000
```

Resolution order per org: `org_quotas` override → plan limit → system default.

## What was previously broken

`org_quotas` (the staff quota/freeze table from V19) was **never read by
anything**. The Javadoc claimed the rate limiter consulted it on every request;
it did not. Staff could set caps and freeze an org, and nothing happened.

This change wires it up for real, via `OrgEntitlementService`, which the API-key
filter now consults. Two consequences:

- **Staff freeze now works.** A frozen org gets `402 Payment Required` on every
  API-key request. That is a behaviour change for any org already flagged frozen
  — check `SELECT org_id FROM org_quotas WHERE frozen` before deploying.
- Existing `api_daily_cap` / `api_rpm_override` rows also take effect for the
  first time.

## Failure states

| Razorpay status | Meaning | Access |
|---|---|---|
| `created` | Subscription made, not paid | Free |
| `authenticated` | Mandate approved, first charge pending | Pro |
| `active` | Paying normally | Pro |
| `pending` | A renewal failed, retries in progress | **Pro — deliberately** |
| `halted` | Retries exhausted | Downgraded to Free |
| `cancelled` / `completed` / `expired` | Ended | Free |

`pending` keeps access on purpose: an expired card mid-cycle should not lock
someone out of documents they are relying on while Razorpay retries.

## Idempotency

Razorpay retries deliveries. `billing_events.razorpay_event_id` is unique, and
the row is inserted *before* the plan change is applied — so a redelivery hits
the constraint and is skipped rather than applying twice.

Every raw payload is stored in `billing_events.payload` for support and disputes.

## Cancellation

Default is cancel-at-period-end: the customer keeps Pro until the period they
paid for runs out. `DELETE /billing/subscription?immediately=true` cuts access
at once — use it for refunds, not for ordinary churn.

## Endpoints

All are ADMIN-only and org-scoped, except the webhook.

| Method | Path |
|---|---|
| GET | `/api/orgs/{orgId}/billing` |
| POST | `/api/orgs/{orgId}/billing/subscription` |
| POST | `/api/orgs/{orgId}/billing/subscription/confirm` |
| DELETE | `/api/orgs/{orgId}/billing/subscription` |
| GET | `/api/orgs/{orgId}/billing/payments` |
| POST | `/api/webhooks/razorpay` (public; HMAC-verified) |

## Not built

- **GST / tax invoices.** Razorpay emits its own receipts; if you need
  GST-compliant invoices with your GSTIN, that is a separate piece of work and
  likely a legal requirement for Indian B2B customers.
- **Proration and plan switching.** Changing between monthly and yearly means
  cancelling and re-subscribing today.
- **Seat-based pricing.** The model here is per-workspace, not per-seat.
- **Dunning emails.** Razorpay notifies the customer; we do not send our own
  "your payment failed" email yet.
