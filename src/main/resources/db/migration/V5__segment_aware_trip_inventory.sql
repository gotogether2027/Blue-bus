-- BLUE BUS V5: replace whole-trip trip_seats sale state with segment-ready physical inventory.
-- Allocation, hold, and booking tables are intentionally deferred to the booking phase.
-- Occupancy will later use half-open trip-stop ranges: int4range(origin_sequence, destination_sequence, '[)').

ALTER TABLE trips
    ADD COLUMN service_date DATE,
    ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata';

UPDATE trips
SET service_date = (scheduled_departure_at AT TIME ZONE 'Asia/Kolkata')::date
WHERE service_date IS NULL;

ALTER TABLE trips
    ALTER COLUMN service_date SET NOT NULL,
    DROP CONSTRAINT uq_trips_bus_departure,
    ADD CONSTRAINT uq_trips_bus_service_date_departure UNIQUE (bus_id, service_date, scheduled_departure_at),
    ADD CONSTRAINT ck_trips_time_zone_not_blank CHECK (length(btrim(time_zone)) > 0);

CREATE INDEX ix_trips_operator_status_service_date ON trips (operator_id, status, service_date);
CREATE INDEX ix_trips_route_service_date_status ON trips (route_id, service_date, status);
CREATE INDEX ix_trips_bus_service_date ON trips (bus_id, service_date);

CREATE TABLE trip_stops (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trips(id),
    route_stop_id UUID,
    location_id UUID NOT NULL REFERENCES locations(id),
    sequence_number INTEGER NOT NULL,
    scheduled_arrival_at TIMESTAMPTZ,
    scheduled_departure_at TIMESTAMPTZ,
    stop_kind VARCHAR(30) NOT NULL,
    stop_status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    distance_km NUMERIC(10,2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_trip_stops_trip_sequence UNIQUE (trip_id, sequence_number),
    CONSTRAINT uq_trip_stops_id_trip UNIQUE (id, trip_id),
    CONSTRAINT ck_trip_stops_sequence CHECK (sequence_number > 0),
    CONSTRAINT ck_trip_stops_kind CHECK (stop_kind IN ('SOURCE', 'INTERMEDIATE', 'DESTINATION')),
    CONSTRAINT ck_trip_stops_status CHECK (stop_status IN ('ACTIVE', 'SKIPPED', 'CANCELLED')),
    CONSTRAINT ck_trip_stops_distance CHECK (distance_km IS NULL OR distance_km >= 0),
    CONSTRAINT ck_trip_stops_times CHECK (
        scheduled_arrival_at IS NULL
        OR scheduled_departure_at IS NULL
        OR scheduled_departure_at >= scheduled_arrival_at
    )
);
CREATE INDEX ix_trip_stops_trip_location ON trip_stops (trip_id, location_id);

CREATE TABLE route_points (
    id UUID PRIMARY KEY,
    route_stop_id UUID NOT NULL REFERENCES route_stops(id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    point_type VARCHAR(30) NOT NULL,
    address VARCHAR(255),
    latitude NUMERIC(9,6),
    longitude NUMERIC(9,6),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_route_points_stop_name UNIQUE (route_stop_id, name),
    CONSTRAINT ck_route_points_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_route_points_type CHECK (point_type IN ('BOARDING', 'DROPPING', 'BOTH')),
    CONSTRAINT ck_route_points_latitude CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_route_points_longitude CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);
CREATE INDEX ix_route_points_stop_type_active ON route_points (route_stop_id, point_type, active);

CREATE TABLE trip_points (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trips(id),
    trip_stop_id UUID NOT NULL,
    source_route_point_id UUID,
    name VARCHAR(160) NOT NULL,
    point_type VARCHAR(30) NOT NULL,
    address VARCHAR(255),
    latitude NUMERIC(9,6),
    longitude NUMERIC(9,6),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_trip_points_stop_same_trip FOREIGN KEY (trip_stop_id, trip_id)
        REFERENCES trip_stops (id, trip_id),
    CONSTRAINT uq_trip_points_stop_name_type UNIQUE (trip_stop_id, name, point_type),
    CONSTRAINT uq_trip_points_id_trip UNIQUE (id, trip_id),
    CONSTRAINT ck_trip_points_name_not_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_trip_points_type CHECK (point_type IN ('BOARDING', 'DROPPING', 'BOTH')),
    CONSTRAINT ck_trip_points_latitude CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_trip_points_longitude CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);
CREATE INDEX ix_trip_points_trip_type_active ON trip_points (trip_id, point_type, active);

CREATE TABLE trip_seat_inventory (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trips(id),
    layout_seat_id UUID NOT NULL,
    seat_layout_id UUID NOT NULL,
    seat_layout_version INTEGER NOT NULL,
    seat_number VARCHAR(20) NOT NULL,
    seat_type VARCHAR(30) NOT NULL,
    deck_number INTEGER NOT NULL,
    row_number INTEGER NOT NULL,
    column_number INTEGER NOT NULL,
    physical_status VARCHAR(30) NOT NULL DEFAULT 'AVAILABLE',
    block_reason VARCHAR(255),
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_trip_seat_inventory_trip_layout FOREIGN KEY (trip_id, seat_layout_id)
        REFERENCES trips (id, seat_layout_id),
    CONSTRAINT fk_trip_seat_inventory_seat_layout FOREIGN KEY (layout_seat_id, seat_layout_id)
        REFERENCES seats (id, seat_layout_id),
    CONSTRAINT uq_trip_seat_inventory_trip_layout_seat UNIQUE (trip_id, layout_seat_id),
    CONSTRAINT uq_trip_seat_inventory_id_trip UNIQUE (id, trip_id),
    CONSTRAINT ck_trip_seat_inventory_layout_version CHECK (seat_layout_version > 0),
    CONSTRAINT ck_trip_seat_inventory_position CHECK (deck_number > 0 AND row_number > 0 AND column_number > 0),
    CONSTRAINT ck_trip_seat_inventory_seat_number CHECK (length(btrim(seat_number)) > 0),
    CONSTRAINT ck_trip_seat_inventory_seat_type CHECK (length(btrim(seat_type)) > 0),
    CONSTRAINT ck_trip_seat_inventory_status CHECK (physical_status IN ('AVAILABLE', 'BLOCKED')),
    CONSTRAINT ck_trip_seat_inventory_version CHECK (version > 0)
);
CREATE INDEX ix_trip_seat_inventory_trip_status ON trip_seat_inventory (trip_id, physical_status);

INSERT INTO trip_stops (
    id,
    trip_id,
    route_stop_id,
    location_id,
    sequence_number,
    scheduled_arrival_at,
    scheduled_departure_at,
    stop_kind,
    stop_status,
    distance_km,
    created_at,
    updated_at
)
SELECT
    gen_random_uuid(),
    t.id,
    rs.id,
    rs.location_id,
    rs.sequence_number,
    CASE
        WHEN rs.arrival_offset_minutes IS NULL THEN NULL
        ELSE t.scheduled_departure_at + (rs.arrival_offset_minutes * INTERVAL '1 minute')
    END,
    CASE
        WHEN rs.departure_offset_minutes IS NULL THEN NULL
        ELSE t.scheduled_departure_at + (rs.departure_offset_minutes * INTERVAL '1 minute')
    END,
    rs.stop_kind,
    'ACTIVE',
    rs.distance_km,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM trips t
JOIN route_stops rs ON rs.route_id = t.route_id;

INSERT INTO trip_seat_inventory (
    id,
    trip_id,
    layout_seat_id,
    seat_layout_id,
    seat_layout_version,
    seat_number,
    seat_type,
    deck_number,
    row_number,
    column_number,
    physical_status,
    block_reason,
    version,
    created_at,
    updated_at
)
SELECT
    ts.id,
    ts.trip_id,
    ts.seat_id,
    ts.seat_layout_id,
    sl.version,
    s.seat_number,
    s.seat_type,
    s.deck_number,
    s.row_number,
    s.column_number,
    CASE WHEN ts.status = 'BLOCKED' THEN 'BLOCKED' ELSE 'AVAILABLE' END,
    NULL,
    1,
    ts.created_at,
    CURRENT_TIMESTAMP
FROM trip_seats ts
JOIN seats s ON s.id = ts.seat_id
JOIN seat_layouts sl ON sl.id = ts.seat_layout_id;

DROP TABLE trip_seats;
