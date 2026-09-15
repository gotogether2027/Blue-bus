-- Payment foundation: provider-neutral attempts, durable provider-event inbox,
-- refund persistence foundation, and transactional outbox. No provider integration.

CREATE TABLE payment_attempts (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL REFERENCES bookings(id),
    user_id UUID NOT NULL REFERENCES users(id),
    provider VARCHAR(50) NOT NULL,
    merchant_reference VARCHAR(100) NOT NULL,
    provider_order_id VARCHAR(150),
    provider_payment_id VARCHAR(150),
    checkout_reference VARCHAR(500),
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    requested_amount NUMERIC(12, 2) NOT NULL,
    captured_amount NUMERIC(12, 2),
    currency VARCHAR(3) NOT NULL,
    booking_payment_expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(30) NOT NULL,
    disposition VARCHAR(30) NOT NULL DEFAULT 'UNAPPLIED',
    provider_status VARCHAR(100),
    failure_code VARCHAR(100),
    resolution_reason VARCHAR(100),
    provider_occurred_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_payment_attempts_status CHECK (
        status IN ('INITIATING', 'PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT ck_payment_attempts_disposition CHECK (
        disposition IN ('UNAPPLIED', 'APPLIED_TO_BOOKING', 'REQUIRES_RESOLUTION')
    ),
    CONSTRAINT ck_payment_attempts_amounts CHECK (
        requested_amount >= 0 AND (captured_amount IS NULL OR captured_amount >= 0)
    ),
    CONSTRAINT ck_payment_attempts_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_attempts_success_fields CHECK (
        status <> 'SUCCEEDED'
        OR (captured_amount IS NOT NULL AND disposition <> 'UNAPPLIED')
    ),
    CONSTRAINT ck_payment_attempts_non_success_unapplied CHECK (
        status = 'SUCCEEDED' OR disposition = 'UNAPPLIED'
    ),
    CONSTRAINT ck_payment_attempts_version CHECK (version > 0)
);

CREATE UNIQUE INDEX uq_payment_attempts_user_idempotency
    ON payment_attempts (user_id, idempotency_key);

CREATE UNIQUE INDEX uq_payment_attempts_provider_merchant_reference
    ON payment_attempts (provider, merchant_reference);

CREATE UNIQUE INDEX uq_payment_attempts_provider_order
    ON payment_attempts (provider, provider_order_id)
    WHERE provider_order_id IS NOT NULL;

CREATE UNIQUE INDEX uq_payment_attempts_provider_payment
    ON payment_attempts (provider, provider_payment_id)
    WHERE provider_payment_id IS NOT NULL;

CREATE UNIQUE INDEX uq_payment_attempts_active_booking
    ON payment_attempts (booking_id)
    WHERE status IN ('INITIATING', 'PENDING');

CREATE UNIQUE INDEX uq_payment_attempts_applied_booking
    ON payment_attempts (booking_id)
    WHERE disposition = 'APPLIED_TO_BOOKING';

CREATE INDEX ix_payment_attempts_booking_created
    ON payment_attempts (booking_id, created_at DESC);

CREATE INDEX ix_payment_attempts_resolution
    ON payment_attempts (created_at)
    WHERE disposition = 'REQUIRES_RESOLUTION';

CREATE TABLE payment_provider_events (
    id UUID PRIMARY KEY,
    provider VARCHAR(50) NOT NULL,
    provider_event_id VARCHAR(150) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payment_attempt_id UUID REFERENCES payment_attempts(id),
    merchant_reference VARCHAR(100),
    provider_order_id VARCHAR(150),
    provider_payment_id VARCHAR(150),
    amount NUMERIC(12, 2),
    currency VARCHAR(3),
    provider_status VARCHAR(100),
    failure_code VARCHAR(100),
    processing_status VARCHAR(30) NOT NULL,
    signature_verified BOOLEAN NOT NULL,
    provider_occurred_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    payload_hash VARCHAR(64) NOT NULL,
    processing_result VARCHAR(100),
    last_error_code VARCHAR(100),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_payment_provider_events_provider_event UNIQUE (provider, provider_event_id),
    CONSTRAINT ck_payment_provider_events_status CHECK (
        processing_status IN (
            'RECEIVED', 'PROCESSING', 'PROCESSED', 'IGNORED',
            'FAILED_RETRYABLE', 'REQUIRES_REVIEW'
        )
    ),
    CONSTRAINT ck_payment_provider_events_verified CHECK (signature_verified),
    CONSTRAINT ck_payment_provider_events_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_payment_provider_events_amount CHECK (amount IS NULL OR amount >= 0),
    CONSTRAINT ck_payment_provider_events_currency CHECK (
        currency IS NULL OR currency ~ '^[A-Z]{3}$'
    ),
    CONSTRAINT ck_payment_provider_events_payload_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_payment_provider_events_processing
    ON payment_provider_events (processing_status, received_at);

CREATE INDEX ix_payment_provider_events_attempt
    ON payment_provider_events (payment_attempt_id, received_at)
    WHERE payment_attempt_id IS NOT NULL;

CREATE TABLE refunds (
    id UUID PRIMARY KEY,
    payment_attempt_id UUID NOT NULL REFERENCES payment_attempts(id),
    booking_id UUID NOT NULL REFERENCES bookings(id),
    provider VARCHAR(50) NOT NULL,
    provider_refund_id VARCHAR(150),
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint VARCHAR(128) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    reason VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    provider_status VARCHAR(100),
    failure_code VARCHAR(100),
    requested_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_refunds_attempt_idempotency UNIQUE (payment_attempt_id, idempotency_key),
    CONSTRAINT ck_refunds_status CHECK (
        status IN ('REQUESTED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_refunds_amount CHECK (amount > 0),
    CONSTRAINT ck_refunds_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_refunds_version CHECK (version > 0)
);

CREATE UNIQUE INDEX uq_refunds_provider_reference
    ON refunds (provider, provider_refund_id)
    WHERE provider_refund_id IS NOT NULL;

CREATE INDEX ix_refunds_payment_created
    ON refunds (payment_attempt_id, created_at);

CREATE INDEX ix_refunds_status_created
    ON refunds (status, created_at);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    schema_version INTEGER NOT NULL,
    correlation_id VARCHAR(100),
    causation_id VARCHAR(100),
    payload_json TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_outbox_events_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_outbox_events_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX ix_outbox_events_unpublished
    ON outbox_events (occurred_at)
    WHERE published_at IS NULL;
