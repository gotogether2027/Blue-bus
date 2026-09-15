-- Phase 9.1: bookings, booking items, and booking passengers (hold-to-book foundation).
-- Payments, tickets, refunds, and booking expiry reaper remain deferred.
-- Fare assumption: total uses trips.base_fare × seat count; tax/fee/discount default to 0 until trip_fares exists.

CREATE TABLE bookings (
    id UUID PRIMARY KEY,
    booking_reference VARCHAR(32) NOT NULL,
    user_id UUID NOT NULL,
    trip_id UUID NOT NULL,
    operator_id UUID NOT NULL,
    hold_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL,
    origin_sequence INTEGER NOT NULL,
    destination_sequence INTEGER NOT NULL,
    origin_trip_stop_id UUID,
    destination_trip_stop_id UUID,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    base_amount NUMERIC(12, 2) NOT NULL,
    tax_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    fee_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    discount_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(12, 2) NOT NULL,
    idempotency_key VARCHAR(100),
    request_fingerprint VARCHAR(128),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bookings_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_bookings_trip
        FOREIGN KEY (trip_id) REFERENCES trips (id),
    CONSTRAINT fk_bookings_hold
        FOREIGN KEY (hold_id) REFERENCES seat_holds (id),
    CONSTRAINT uq_bookings_booking_reference UNIQUE (booking_reference),
    CONSTRAINT uq_bookings_hold UNIQUE (hold_id),
    CONSTRAINT ck_bookings_status CHECK (status IN (
        'INITIATED',
        'PENDING_PAYMENT',
        'CONFIRMED',
        'CANCELLED',
        'EXPIRED',
        'REFUND_PENDING',
        'REFUNDED'
    )),
    CONSTRAINT ck_bookings_destination_after_origin
        CHECK (destination_sequence > origin_sequence),
    CONSTRAINT ck_bookings_sequences_positive
        CHECK (origin_sequence > 0 AND destination_sequence > 0),
    CONSTRAINT ck_bookings_amounts_non_negative
        CHECK (
            base_amount >= 0
            AND tax_amount >= 0
            AND fee_amount >= 0
            AND discount_amount >= 0
            AND total_amount >= 0
        ),
    CONSTRAINT ck_bookings_total_equation
        CHECK (total_amount = base_amount + tax_amount + fee_amount - discount_amount),
    CONSTRAINT ck_bookings_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_bookings_version CHECK (version > 0)
);

CREATE UNIQUE INDEX uq_bookings_user_idempotency
    ON bookings (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_bookings_user_created
    ON bookings (user_id, created_at DESC);

CREATE INDEX ix_bookings_trip_status
    ON bookings (trip_id, status);

CREATE INDEX ix_bookings_operator_status_created
    ON bookings (operator_id, status, created_at DESC);

CREATE TABLE booking_passengers (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL,
    full_name VARCHAR(120) NOT NULL,
    age INTEGER,
    gender VARCHAR(30),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_booking_passengers_booking
        FOREIGN KEY (booking_id) REFERENCES bookings (id) ON DELETE CASCADE,
    CONSTRAINT ck_booking_passengers_full_name
        CHECK (char_length(trim(full_name)) >= 1),
    CONSTRAINT ck_booking_passengers_age
        CHECK (age IS NULL OR (age >= 0 AND age <= 120))
);

CREATE INDEX ix_booking_passengers_booking
    ON booking_passengers (booking_id);

CREATE TABLE booking_items (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL,
    inventory_id UUID NOT NULL,
    passenger_id UUID,
    seat_number VARCHAR(20) NOT NULL,
    seat_type VARCHAR(30) NOT NULL,
    origin_sequence INTEGER NOT NULL,
    destination_sequence INTEGER NOT NULL,
    base_amount NUMERIC(12, 2) NOT NULL,
    tax_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    fee_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    discount_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(12, 2) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_booking_items_booking
        FOREIGN KEY (booking_id) REFERENCES bookings (id) ON DELETE CASCADE,
    CONSTRAINT fk_booking_items_inventory_same_trip
        FOREIGN KEY (inventory_id)
        REFERENCES trip_seat_inventory (id),
    CONSTRAINT fk_booking_items_passenger
        FOREIGN KEY (passenger_id) REFERENCES booking_passengers (id) ON DELETE SET NULL,
    CONSTRAINT uq_booking_items_booking_inventory UNIQUE (booking_id, inventory_id),
    CONSTRAINT ck_booking_items_destination_after_origin
        CHECK (destination_sequence > origin_sequence),
    CONSTRAINT ck_booking_items_amounts_non_negative
        CHECK (
            base_amount >= 0
            AND tax_amount >= 0
            AND fee_amount >= 0
            AND discount_amount >= 0
            AND total_amount >= 0
        ),
    CONSTRAINT ck_booking_items_total_equation
        CHECK (total_amount = base_amount + tax_amount + fee_amount - discount_amount),
    CONSTRAINT ck_booking_items_status
        CHECK (status IN ('ACTIVE', 'CANCELLED', 'REFUNDED'))
);

CREATE INDEX ix_booking_items_booking
    ON booking_items (booking_id);

CREATE INDEX ix_booking_items_inventory
    ON booking_items (inventory_id);
