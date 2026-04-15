-- V6: Notifications
CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type VARCHAR(64) NOT NULL,
    title VARCHAR(512) NOT NULL,
    body TEXT,
    entity_type VARCHAR(64),
    entity_id UUID,
    metadata TEXT,
    read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_user ON notifications (user_id, read, created_at DESC);
