-- Customer booking cancellation foundation.
-- Only unpaid PENDING_PAYMENT cancellation is implemented; confirmed-booking refund policy is deferred.

CREATE TABLE booking_cancellations (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL REFERENCES bookings(id),
    requested_by_user_id UUID NOT NULL REFERENCES users(id),
    previous_status VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    reason VARCHAR(500),
    policy_code VARCHAR(100) NOT NULL,
    refundable_amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    cancelled_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_booking_cancellations_booking UNIQUE (booking_id),
    CONSTRAINT ck_booking_cancellations_previous_status
        CHECK (previous_status = 'PENDING_PAYMENT'),
    CONSTRAINT ck_booking_cancellations_status
        CHECK (status = 'COMPLETED'),
    CONSTRAINT ck_booking_cancellations_policy
        CHECK (policy_code = 'UNPAID_CUSTOMER_CANCELLATION_V1'),
    CONSTRAINT ck_booking_cancellations_refundable_amount
        CHECK (refundable_amount = 0),
    CONSTRAINT ck_booking_cancellations_currency
        CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX ix_booking_cancellations_user_created
    ON booking_cancellations (requested_by_user_id, created_at DESC);

-- Existing (trip_id, location_id) index is trip-first and cannot efficiently lead a
-- location/date customer search.
CREATE INDEX ix_trip_stops_location_trip_sequence
    ON trip_stops (location_id, trip_id, sequence_number)
    WHERE stop_status = 'ACTIVE';
