CREATE TABLE templates (
    id UUID PRIMARY KEY,
    name VARCHAR(512) NOT NULL,
    created_by VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE template_versions (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES templates (id) ON DELETE CASCADE,
    version_number INT NOT NULL,
    layout_json JSONB NOT NULL,
    variables JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_template_version UNIQUE (template_id, version_number)
);

CREATE INDEX idx_template_versions_template_id ON template_versions (template_id);

CREATE TABLE generated_documents (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES templates (id) ON DELETE CASCADE,
    version_id UUID NOT NULL REFERENCES template_versions (id) ON DELETE CASCADE,
    input_data JSONB,
    file_url VARCHAR(1024),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_generated_documents_template_id ON generated_documents (template_id);
CREATE INDEX idx_generated_documents_version_id ON generated_documents (version_id);
