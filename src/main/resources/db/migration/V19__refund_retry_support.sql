-- Phase 9.6 refund reliability: durable due-refund lookup and bounded retry metadata.
-- refunds remains the work record; no separate queue table.

ALTER TABLE refunds
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_retry_at TIMESTAMPTZ;

ALTER TABLE refunds
    ADD CONSTRAINT ck_refunds_attempt_count CHECK (attempt_count >= 0);

CREATE INDEX ix_refunds_due_retry
    ON refunds (next_retry_at, created_at)
    WHERE provider_refund_id IS NULL
      AND status IN ('REQUESTED', 'PROCESSING', 'FAILED');
