CREATE TABLE marketplace_listings (
    id UUID PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(256) NOT NULL,
    description TEXT,
    author_id UUID REFERENCES users(id) ON DELETE SET NULL,
    author_name VARCHAR(256),
    org_id UUID REFERENCES organizations(id) ON DELETE SET NULL,
    source_template_id UUID REFERENCES templates(id) ON DELETE SET NULL,
    thumbnail_url VARCHAR(1024),
    category VARCHAR(64),
    tags TEXT,
    install_count INT NOT NULL DEFAULT 0,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_marketplace_published ON marketplace_listings(published, created_at DESC);
CREATE INDEX idx_marketplace_category ON marketplace_listings(category);
