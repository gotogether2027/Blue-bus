-- Phase 1 channel-agnostic notification foundation.
-- Notifications are created from existing outbox events (and optional RabbitMQ
-- delivery of those events). published_at / rabbit_published_at are unchanged.

CREATE TABLE notification_preferences (
    user_id UUID PRIMARY KEY REFERENCES users (id),
    email_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sms_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    whatsapp_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    source_event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    template_code VARCHAR(100) NOT NULL,
    logical_key VARCHAR(150) NOT NULL,
    subject VARCHAR(255),
    body TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    provider_message_id VARCHAR(150),
    failure_code VARCHAR(100),
    failure_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,
    CONSTRAINT ck_notifications_channel CHECK (channel IN ('EMAIL', 'SMS', 'WHATSAPP')),
    CONSTRAINT ck_notifications_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_notifications_attempt_count CHECK (attempt_count >= 0)
);

CREATE UNIQUE INDEX ux_notifications_source_event_channel
    ON notifications (user_id, event_type, channel, source_event_id);

CREATE UNIQUE INDEX ux_notifications_logical_key
    ON notifications (user_id, event_type, channel, logical_key);

CREATE INDEX ix_notifications_user_created
    ON notifications (user_id, created_at DESC);

CREATE INDEX ix_notifications_pending_retry
    ON notifications (next_retry_at, created_at)
    WHERE status = 'PENDING';
