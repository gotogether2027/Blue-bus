-- Phase 9.4A: ticket foundation — immutable customer-facing travel documents for confirmed bookings.
-- PDF/QR, notifications, and event-driven issuance remain deferred.

CREATE TABLE tickets (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL,
    ticket_number VARCHAR(16) NOT NULL,
    status VARCHAR(30) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    user_id UUID NOT NULL,
    booking_reference VARCHAR(32) NOT NULL,
    trip_id UUID NOT NULL,
    operator_id UUID NOT NULL,
    operator_name VARCHAR(255) NOT NULL,
    origin_stop_id UUID NOT NULL,
    destination_stop_id UUID NOT NULL,
    origin_stop_name VARCHAR(200) NOT NULL,
    destination_stop_name VARCHAR(200) NOT NULL,
    scheduled_departure_at TIMESTAMPTZ NOT NULL,
    scheduled_arrival_at TIMESTAMPTZ NOT NULL,
    currency VARCHAR(3) NOT NULL,
    total_amount NUMERIC(12, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tickets_booking
        FOREIGN KEY (booking_id) REFERENCES bookings (id),
    CONSTRAINT fk_tickets_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_tickets_trip
        FOREIGN KEY (trip_id) REFERENCES trips (id),
    CONSTRAINT uq_tickets_booking UNIQUE (booking_id),
    CONSTRAINT uq_tickets_ticket_number UNIQUE (ticket_number),
    CONSTRAINT ck_tickets_status CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT ck_tickets_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_tickets_total_amount CHECK (total_amount >= 0),
    CONSTRAINT ck_tickets_ticket_number
        CHECK (ticket_number ~ '^BB[A-Z0-9]{8}$')
);

CREATE INDEX ix_tickets_user_issued
    ON tickets (user_id, issued_at DESC);

CREATE INDEX ix_tickets_ticket_number
    ON tickets (ticket_number);

CREATE TABLE ticket_passengers (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL,
    booking_passenger_id UUID,
    passenger_name VARCHAR(120) NOT NULL,
    age INTEGER,
    gender VARCHAR(30),
    seat_label VARCHAR(20) NOT NULL,
    origin_stop_name VARCHAR(200) NOT NULL,
    destination_stop_name VARCHAR(200) NOT NULL,
    fare_amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ticket_passengers_ticket
        FOREIGN KEY (ticket_id) REFERENCES tickets (id) ON DELETE CASCADE,
    CONSTRAINT fk_ticket_passengers_booking_passenger
        FOREIGN KEY (booking_passenger_id) REFERENCES booking_passengers (id) ON DELETE SET NULL,
    CONSTRAINT ck_ticket_passengers_passenger_name
        CHECK (char_length(trim(passenger_name)) >= 1),
    CONSTRAINT ck_ticket_passengers_age
        CHECK (age IS NULL OR (age >= 0 AND age <= 120)),
    CONSTRAINT ck_ticket_passengers_fare_amount CHECK (fare_amount >= 0),
    CONSTRAINT ck_ticket_passengers_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX ix_ticket_passengers_ticket
    ON ticket_passengers (ticket_id);
