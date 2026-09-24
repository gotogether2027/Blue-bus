-- Phase 1 RabbitMQ outbox publishing. published_at remains the local-processor
-- completion timestamp (BOOKING_CONFIRMED → ticket). Broker delivery uses a
-- separate timestamp so a successful RabbitMQ confirm cannot skip local work.
ALTER TABLE outbox_events
    ADD COLUMN rabbit_published_at TIMESTAMPTZ,
    ADD COLUMN rabbit_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN rabbit_next_retry_at TIMESTAMPTZ;

ALTER TABLE outbox_events
    ADD CONSTRAINT ck_outbox_events_rabbit_attempt_count CHECK (rabbit_attempt_count >= 0);

CREATE INDEX ix_outbox_events_rabbit_unpublished
    ON outbox_events (occurred_at)
    WHERE rabbit_published_at IS NULL;

CREATE INDEX ix_outbox_events_rabbit_retry
    ON outbox_events (rabbit_next_retry_at, occurred_at)
    WHERE rabbit_published_at IS NULL;

-- Consumer inbox. Unique (event_id, consumer_name) makes at-least-once
-- RabbitMQ redelivery safe. Do not treat this as exactly-once delivery.
CREATE TABLE processed_events (
    event_id UUID NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, consumer_name)
);
