-- BLUE BUS Phase 3: scheduled trips and their initial per-trip seat inventory only.
-- The added composite candidate keys allow PostgreSQL to enforce operator ownership
-- and the selected reusable layout without changing the Phase 2 migrations.

ALTER TABLE buses
    ADD CONSTRAINT uq_buses_id_operator UNIQUE (id, operator_id),
    ADD CONSTRAINT uq_buses_id_seat_layout UNIQUE (id, seat_layout_id);

ALTER TABLE routes
    ADD CONSTRAINT uq_routes_id_operator UNIQUE (id, operator_id);

ALTER TABLE seats
    ADD CONSTRAINT uq_seats_id_layout UNIQUE (id, seat_layout_id);

CREATE TABLE trips (
    id UUID PRIMARY KEY,
    operator_id UUID NOT NULL REFERENCES operators(id),
    bus_id UUID NOT NULL,
    route_id UUID NOT NULL,
    seat_layout_id UUID NOT NULL REFERENCES seat_layouts(id),
    scheduled_departure_at TIMESTAMPTZ NOT NULL,
    scheduled_arrival_at TIMESTAMPTZ NOT NULL,
    base_fare NUMERIC(12,2) NOT NULL,
    booking_opens_at TIMESTAMPTZ NOT NULL,
    booking_closes_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_trips_bus_same_operator FOREIGN KEY (bus_id, operator_id)
        REFERENCES buses (id, operator_id),
    CONSTRAINT fk_trips_route_same_operator FOREIGN KEY (route_id, operator_id)
        REFERENCES routes (id, operator_id),
    CONSTRAINT fk_trips_bus_selected_layout FOREIGN KEY (bus_id, seat_layout_id)
        REFERENCES buses (id, seat_layout_id),
    CONSTRAINT uq_trips_bus_departure UNIQUE (bus_id, scheduled_departure_at),
    CONSTRAINT uq_trips_id_layout UNIQUE (id, seat_layout_id),
    CONSTRAINT ck_trips_arrival_after_departure CHECK (scheduled_arrival_at > scheduled_departure_at),
    CONSTRAINT ck_trips_base_fare_non_negative CHECK (base_fare >= 0),
    CONSTRAINT ck_trips_booking_window CHECK (
        booking_opens_at < booking_closes_at
        AND booking_closes_at <= scheduled_departure_at
    ),
    CONSTRAINT ck_trips_status CHECK (status IN (
        'DRAFT', 'SCHEDULED', 'ON_SALE', 'CLOSED', 'DEPARTED', 'COMPLETED', 'CANCELLED'
    ))
);
CREATE INDEX ix_trips_operator_status_departure ON trips (operator_id, status, scheduled_departure_at);
CREATE INDEX ix_trips_route_status_departure ON trips (route_id, status, scheduled_departure_at);
CREATE INDEX ix_trips_bus_departure ON trips (bus_id, scheduled_departure_at);

CREATE TABLE trip_seats (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trips(id),
    seat_id UUID NOT NULL,
    seat_layout_id UUID NOT NULL,
    fare NUMERIC(12,2) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'AVAILABLE',
    locked_until TIMESTAMPTZ,
    booking_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_trip_seats_trip_layout FOREIGN KEY (trip_id, seat_layout_id)
        REFERENCES trips (id, seat_layout_id),
    CONSTRAINT fk_trip_seats_seat_layout FOREIGN KEY (seat_id, seat_layout_id)
        REFERENCES seats (id, seat_layout_id),
    CONSTRAINT uq_trip_seats_trip_seat UNIQUE (trip_id, seat_id),
    CONSTRAINT ck_trip_seats_fare_non_negative CHECK (fare >= 0),
    CONSTRAINT ck_trip_seats_status CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED', 'BLOCKED'))
);
CREATE INDEX ix_trip_seats_trip_status ON trip_seats (trip_id, status);
CREATE INDEX ix_trip_seats_locked_until ON trip_seats (locked_until) WHERE locked_until IS NOT NULL;
