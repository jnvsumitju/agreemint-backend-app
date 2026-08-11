-- Marketplace listings carry their own content instead of pointing at a live
-- template.
--
-- V5 modelled a listing as a reference: `source_template_id REFERENCES
-- templates(id) ON DELETE SET NULL`. Three problems followed from that, and all
-- three are fixed by snapshotting at publish time:
--
--   1. Deleting the source template left a listing that installed nothing —
--      the reference became NULL and the install path had no content to copy.
--   2. A publisher kept editing the template after publishing, so what an
--      installer received silently changed under them. Nobody agreed to that.
--   3. Installing had to reach into another workspace's live table to read
--      content, which is a cross-tenant read the authorization model would
--      rather not have to justify.
--
-- `source_template_id` is kept, but only as provenance — "this listing came
-- from that template". It is no longer read to install.
--
-- JSONB matches template_versions.layout_json (V1__init.sql).

ALTER TABLE marketplace_listings
    ADD COLUMN layout_json JSONB,
    ADD COLUMN variables JSONB,
    ADD COLUMN source_version_id UUID;

-- Existing rows predate snapshotting and have no content. There are no real
-- listings yet (publish had no UI), so there is nothing to back-fill; any row
-- that does exist is unpublished here rather than left installable-but-empty.
UPDATE marketplace_listings SET published = FALSE WHERE layout_json IS NULL;

-- "My workspace's listings" — the management screen that makes unpublish
-- reachable. Without moderation this is the only way to withdraw a listing, so
-- the query behind it should not be a sequential scan.
CREATE INDEX idx_marketplace_org ON marketplace_listings (org_id, created_at DESC);
