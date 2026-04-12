CREATE TABLE template_drafts (
    template_id UUID PRIMARY KEY REFERENCES templates (id) ON DELETE CASCADE,
    layout_json JSONB NOT NULL,
    variables JSONB,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
