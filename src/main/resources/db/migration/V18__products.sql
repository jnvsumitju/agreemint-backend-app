-- V18: Product concept — a grouping layer above templates.
--
-- Each org now has its own list of products (e.g. "Mortgage Loans",
-- "Auto Lease"); every new template must be assigned to one. Legacy
-- templates keep a null product_id so this migration is backwards-
-- compatible — admins can reassign them later from the Products tab.

CREATE TABLE products (
    id          UUID PRIMARY KEY,
    org_id      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name        VARCHAR(256) NOT NULL,
    description TEXT,
    created_by  UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_products_org_name UNIQUE (org_id, name)
);

CREATE INDEX idx_products_org ON products(org_id);

-- Templates belong to at most one product. RESTRICT protects products
-- that are in use — the API surface refuses delete in v1, but the FK
-- enforces it even if something slips past the service layer.
ALTER TABLE templates
    ADD COLUMN product_id UUID REFERENCES products(id) ON DELETE RESTRICT;

CREATE INDEX idx_templates_product ON templates(product_id);
