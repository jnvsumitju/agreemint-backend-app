-- =============================================
-- V8: Widen logo_url and avatar_url to TEXT
--     to support base64-encoded image data
-- =============================================

ALTER TABLE organizations ALTER COLUMN logo_url TYPE TEXT;
ALTER TABLE users ALTER COLUMN avatar_url TYPE TEXT;
