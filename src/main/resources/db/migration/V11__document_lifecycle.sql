-- V11: Document lifecycle management
-- Adds lifecycle tracking, approval workflows, and timeline events

-- 1) Extend generated_documents with lifecycle fields
ALTER TABLE generated_documents
    ADD COLUMN lifecycle_status VARCHAR(32) DEFAULT 'DRAFT',
    ADD COLUMN title VARCHAR(512),
    ADD COLUMN description TEXT,
    ADD COLUMN org_id UUID REFERENCES organizations(id) ON DELETE CASCADE,
    ADD COLUMN created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN expires_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_gendocs_lifecycle ON generated_documents(org_id, lifecycle_status);
CREATE INDEX idx_gendocs_expires ON generated_documents(expires_at) WHERE expires_at IS NOT NULL;

-- 2) Approval workflows (one per document)
CREATE TABLE approval_workflows (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES generated_documents(id) ON DELETE CASCADE,
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_approval_workflows_doc ON approval_workflows(document_id);
CREATE INDEX idx_approval_workflows_org ON approval_workflows(org_id);

-- 3) Approval steps (sequential within a workflow)
CREATE TABLE approval_steps (
    id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL REFERENCES approval_workflows(id) ON DELETE CASCADE,
    step_order INT NOT NULL,
    assignee_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_label VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    comment TEXT,
    decided_at TIMESTAMPTZ,
    CONSTRAINT uq_workflow_step_order UNIQUE (workflow_id, step_order)
);

CREATE INDEX idx_approval_steps_workflow ON approval_steps(workflow_id);
CREATE INDEX idx_approval_steps_assignee ON approval_steps(assignee_id);

-- 4) Document lifecycle events (append-only timeline)
CREATE TABLE document_lifecycle_events (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES generated_documents(id) ON DELETE CASCADE,
    actor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    actor_name VARCHAR(256),
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    comment TEXT,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_lifecycle_events_doc ON document_lifecycle_events(document_id, created_at DESC);
