-- Indexes for the staff audit view.
--
-- activity_log had exactly one index, (org_id, created_at DESC), which serves
-- the customer-facing per-org feed. The admin audit list is a different shape:
-- its default view has no filters at all and orders by created_at DESC across
-- every tenant. org_id leads the existing index, so it cannot satisfy that
-- ordering — Postgres fell back to a sequential scan plus a top-N sort, and
-- because the unfiltered page is always full the paging COUNT ran as a second
-- unbounded scan. Two O(N) passes per request, on a table nothing ever prunes.

-- Global newest-first list: index scan + LIMIT, no sort.
CREATE INDEX idx_activity_log_created_at ON activity_log (created_at DESC);

-- Scoped to one actor — the "View audit" deep link from a user's detail page.
-- The user_id FK does not create an index in Postgres.
CREATE INDEX idx_activity_log_user ON activity_log (user_id, created_at DESC);

-- Case-insensitive prefix filter on action. text_pattern_ops is required for
-- LIKE 'x%' to use the index; the default collation's operator class will not.
CREATE INDEX idx_activity_log_action_lower
    ON activity_log (lower(action) text_pattern_ops, created_at DESC);
