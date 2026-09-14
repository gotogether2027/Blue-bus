-- BLUE BUS V7: seat hold aggregate for temporary multi-seat segment reservations.
-- Occupancy remains trip_seat_allocations; holds own HELD rows via hold_id.
-- Bookings, payments, and customer APIs remain deferred. No expiry scheduler.

CREATE TABLE seat_holds (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trips(id),
    user_id UUID,
    origin_sequence INTEGER NOT NULL,
    destination_sequence INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    idempotency_key VARCHAR(100),
    request_fingerprint VARCHAR(128),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_seat_holds_destination_after_origin
        CHECK (destination_sequence > origin_sequence),
    CONSTRAINT ck_seat_holds_sequences_positive
        CHECK (origin_sequence > 0 AND destination_sequence > 0),
    CONSTRAINT ck_seat_holds_status
        CHECK (status IN ('ACTIVE', 'CONSUMED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_seat_holds_version
        CHECK (version > 0)
);

-- Authenticated idempotency only. Anonymous (NULL user_id) keys are not unique yet.
CREATE UNIQUE INDEX uq_seat_holds_user_idempotency
    ON seat_holds (user_id, idempotency_key)
    WHERE user_id IS NOT NULL AND idempotency_key IS NOT NULL;

CREATE INDEX ix_seat_holds_trip_status_expires
    ON seat_holds (trip_id, status, expires_at);
CREATE INDEX ix_seat_holds_expires_at
    ON seat_holds (expires_at)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_seat_holds_user_id
    ON seat_holds (user_id)
    WHERE user_id IS NOT NULL;

ALTER TABLE trip_seat_allocations
    ADD CONSTRAINT fk_trip_seat_allocations_hold
    FOREIGN KEY (hold_id) REFERENCES seat_holds(id);
