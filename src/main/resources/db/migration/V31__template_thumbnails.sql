-- Template preview images, stored in R2 and referenced by key.
--
-- Two keys, not one, because they answer different questions and one would
-- overwrite the other:
--
--   draft_thumbnail_key  what the template looks like RIGHT NOW, refreshed
--                        while someone edits. Shows work in progress.
--   thumbnail_key        what the last COMMITTED version looks like. This is
--                        what the first-party templates publish to the public
--                        bucket for crixaa.com, where an in-progress edit must
--                        never appear.
--
-- Keys rather than URLs: a private thumbnail is served through a short-lived
-- presigned URL that expires, so storing the URL would persist something that
-- stops working. The key is stable; the URL is minted per response.
ALTER TABLE templates
    ADD COLUMN draft_thumbnail_key VARCHAR(512),
    ADD COLUMN thumbnail_key VARCHAR(512),
    -- Drives the 60-second capture: the console asks for a refresh only when
    -- the layout changed since this moment, so an idle editor uploads nothing.
    ADD COLUMN thumbnail_updated_at TIMESTAMPTZ;
