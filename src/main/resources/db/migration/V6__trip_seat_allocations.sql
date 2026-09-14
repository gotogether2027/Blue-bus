-- BLUE BUS V6: segment-aware trip seat allocations (occupancy).
-- Physical inventory remains trip_seat_inventory (AVAILABLE/BLOCKED only).
-- Occupancy is half-open stop-sequence ranges: int4range(origin, destination, '[)').
-- Seat holds, bookings, and payments remain deferred.

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE trip_seat_allocations (
    id UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES trips(id),
    inventory_id UUID NOT NULL,
    origin_sequence INTEGER NOT NULL,
    destination_sequence INTEGER NOT NULL,
    segment_range INT4RANGE NOT NULL,
    state VARCHAR(30) NOT NULL,
    hold_id UUID,
    booking_item_id UUID,
    expires_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_trip_seat_allocations_inventory_same_trip
        FOREIGN KEY (inventory_id, trip_id)
        REFERENCES trip_seat_inventory (id, trip_id),
    CONSTRAINT ck_trip_seat_allocations_destination_after_origin
        CHECK (destination_sequence > origin_sequence),
    CONSTRAINT ck_trip_seat_allocations_sequences_positive
        CHECK (origin_sequence > 0 AND destination_sequence > 0),
    CONSTRAINT ck_trip_seat_allocations_range_matches_sequences
        CHECK (segment_range = int4range(origin_sequence, destination_sequence, '[)')),
    CONSTRAINT ck_trip_seat_allocations_state
        CHECK (state IN ('HELD', 'BOOKED', 'EXPIRED', 'CANCELLED', 'RELEASED', 'BLOCKED')),
    CONSTRAINT ck_trip_seat_allocations_held_requires_expiry
        CHECK (state <> 'HELD' OR expires_at IS NOT NULL),
    CONSTRAINT ck_trip_seat_allocations_version
        CHECK (version > 0),
    CONSTRAINT ex_trip_seat_allocations_no_overlap
        EXCLUDE USING gist (
            inventory_id WITH =,
            segment_range WITH &&
        )
        WHERE (state IN ('HELD', 'BOOKED', 'BLOCKED'))
);

CREATE INDEX ix_trip_seat_allocations_trip_state
    ON trip_seat_allocations (trip_id, state);
CREATE INDEX ix_trip_seat_allocations_inventory_state
    ON trip_seat_allocations (inventory_id, state);
CREATE INDEX ix_trip_seat_allocations_hold_id
    ON trip_seat_allocations (hold_id)
    WHERE hold_id IS NOT NULL;
CREATE INDEX ix_trip_seat_allocations_booking_item_id
    ON trip_seat_allocations (booking_item_id)
    WHERE booking_item_id IS NOT NULL;
CREATE INDEX ix_trip_seat_allocations_held_expiry
    ON trip_seat_allocations (expires_at)
    WHERE state = 'HELD';
