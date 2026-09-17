-- INITIATING payment recovery: durable retry metadata for attempts that reserved
-- locally but never persisted a provider order id. payment_attempts remains the
-- work record; no separate queue table.

ALTER TABLE payment_attempts
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_retry_at TIMESTAMPTZ;

ALTER TABLE payment_attempts
    ADD CONSTRAINT ck_payment_attempts_attempt_count CHECK (attempt_count >= 0);

CREATE INDEX ix_payment_attempts_initiating_retry
    ON payment_attempts (next_retry_at, created_at)
    WHERE status = 'INITIATING'
      AND provider_order_id IS NULL;
