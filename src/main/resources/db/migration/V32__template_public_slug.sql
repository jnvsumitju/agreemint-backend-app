-- The slug a template is published under on crixaa.com.
--
-- Set only for the first-party templates the seeder installs into the publisher
-- org, where it is the bundle's filename: `free-gst-invoice-template`. Every
-- customer template leaves this null.
--
-- It exists because the marketing site addresses templates by slug and has no
-- way to learn a UUID. Its card grid reads thumbnails straight out of the
-- public bucket at a URL it builds from the slug in its own MDX frontmatter, so
-- the object has to be keyed the same way. It is also the flag that decides
-- whether a committed thumbnail is mirrored publicly at all — a template with
-- no slug has no page to appear on, so the public bucket holds exactly the set
-- of images crixaa.com asks for and nothing else.
--
-- Unique, and only among the rows that have one: two templates claiming the
-- same slug would silently overwrite each other's published image.
ALTER TABLE templates ADD COLUMN public_slug VARCHAR(160);

CREATE UNIQUE INDEX uq_templates_public_slug ON templates (public_slug)
    WHERE public_slug IS NOT NULL;
