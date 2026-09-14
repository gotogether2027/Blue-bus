-- BLUE BUS Phase 2: identity, operator, fleet, locations, and routes only.
-- `seats` is the reusable-layout seat definition described as `layout_seats` in the architecture.

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(320),
    phone_e164 VARCHAR(20),
    password_hash VARCHAR(255),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    email_verified_at TIMESTAMPTZ,
    phone_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'INACTIVE')),
    CONSTRAINT ck_users_email_not_blank CHECK (email IS NULL OR length(btrim(email)) > 0),
    CONSTRAINT ck_users_phone_not_blank CHECK (phone_e164 IS NULL OR length(btrim(phone_e164)) > 0)
);
CREATE UNIQUE INDEX uq_users_email_normalized ON users (lower(email)) WHERE email IS NOT NULL;
CREATE UNIQUE INDEX uq_users_phone_e164 ON users (phone_e164) WHERE phone_e164 IS NOT NULL;
CREATE INDEX ix_users_status ON users (status);

CREATE TABLE roles (
    id UUID PRIMARY KEY,
    code VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    scope VARCHAR(20) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_roles_scope CHECK (scope IN ('PLATFORM', 'OPERATOR'))
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id),
    role_id UUID NOT NULL REFERENCES roles(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX ix_user_roles_role_id ON user_roles (role_id);

CREATE TABLE operators (
    id UUID PRIMARY KEY,
    legal_name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    support_email VARCHAR(320),
    support_phone_e164 VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_operators_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'INACTIVE')),
    CONSTRAINT ck_operators_legal_name_not_blank CHECK (length(btrim(legal_name)) > 0),
    CONSTRAINT ck_operators_display_name_not_blank CHECK (length(btrim(display_name)) > 0)
);
CREATE INDEX ix_operators_status ON operators (status);

CREATE TABLE operator_users (
    operator_id UUID NOT NULL REFERENCES operators(id),
    user_id UUID NOT NULL REFERENCES users(id),
    role_id UUID NOT NULL REFERENCES roles(id),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (operator_id, user_id),
    CONSTRAINT ck_operator_users_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE INDEX ix_operator_users_user_status ON operator_users (user_id, status);
CREATE INDEX ix_operator_users_role_id ON operator_users (role_id);

CREATE OR REPLACE FUNCTION ensure_role_scope()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_TABLE_NAME = 'user_roles' AND NOT EXISTS (
        SELECT 1 FROM roles WHERE id = NEW.role_id AND scope = 'PLATFORM'
    ) THEN
        RAISE EXCEPTION 'user_roles requires a PLATFORM role';
    END IF;
    IF TG_TABLE_NAME = 'operator_users' AND NOT EXISTS (
        SELECT 1 FROM roles WHERE id = NEW.role_id AND scope = 'OPERATOR'
    ) THEN
        RAISE EXCEPTION 'operator_users requires an OPERATOR role';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_user_roles_role_scope
BEFORE INSERT OR UPDATE OF role_id ON user_roles
FOR EACH ROW EXECUTE FUNCTION ensure_role_scope();

CREATE TRIGGER trg_operator_users_role_scope
BEFORE INSERT OR UPDATE OF role_id ON operator_users
FOR EACH ROW EXECUTE FUNCTION ensure_role_scope();

CREATE TABLE bus_types (
    id UUID PRIMARY KEY,
    code VARCHAR(60) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    amenities JSONB NOT NULL DEFAULT '{}'::jsonb,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_bus_types_code_not_blank CHECK (length(btrim(code)) > 0),
    CONSTRAINT ck_bus_types_name_not_blank CHECK (length(btrim(display_name)) > 0)
);
CREATE INDEX ix_bus_types_active ON bus_types (active);

CREATE TABLE seat_layouts (
    id UUID PRIMARY KEY,
    operator_id UUID NOT NULL REFERENCES operators(id),
    name VARCHAR(120) NOT NULL,
    version INTEGER NOT NULL,
    deck_count INTEGER NOT NULL DEFAULT 1,
    row_count INTEGER NOT NULL,
    column_count INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_seat_layouts_owner_name_version UNIQUE (operator_id, name, version),
    CONSTRAINT uq_seat_layouts_id_owner UNIQUE (id, operator_id),
    CONSTRAINT ck_seat_layouts_dimensions CHECK (deck_count > 0 AND row_count > 0 AND column_count > 0),
    CONSTRAINT ck_seat_layouts_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_seat_layouts_name_not_blank CHECK (length(btrim(name)) > 0)
);
CREATE INDEX ix_seat_layouts_owner_status ON seat_layouts (operator_id, status);

CREATE TABLE seats (
    id UUID PRIMARY KEY,
    seat_layout_id UUID NOT NULL REFERENCES seat_layouts(id),
    seat_number VARCHAR(20) NOT NULL,
    deck_number INTEGER NOT NULL DEFAULT 1,
    row_number INTEGER NOT NULL,
    column_number INTEGER NOT NULL,
    seat_type VARCHAR(30) NOT NULL,
    sellable BOOLEAN NOT NULL DEFAULT TRUE,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_seats_layout_number UNIQUE (seat_layout_id, seat_number),
    CONSTRAINT uq_seats_layout_position UNIQUE (seat_layout_id, deck_number, row_number, column_number),
    CONSTRAINT ck_seats_position CHECK (deck_number > 0 AND row_number > 0 AND column_number > 0),
    CONSTRAINT ck_seats_number_not_blank CHECK (length(btrim(seat_number)) > 0),
    CONSTRAINT ck_seats_type_not_blank CHECK (length(btrim(seat_type)) > 0)
);
CREATE INDEX ix_seats_layout_id ON seats (seat_layout_id);

CREATE TABLE buses (
    id UUID PRIMARY KEY,
    operator_id UUID NOT NULL REFERENCES operators(id),
    bus_type_id UUID NOT NULL REFERENCES bus_types(id),
    seat_layout_id UUID NOT NULL,
    registration_number VARCHAR(30) NOT NULL,
    display_name VARCHAR(120),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_buses_registration_number UNIQUE (registration_number),
    CONSTRAINT fk_buses_seat_layout_same_operator FOREIGN KEY (seat_layout_id, operator_id)
        REFERENCES seat_layouts (id, operator_id),
    CONSTRAINT ck_buses_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'MAINTENANCE')),
    CONSTRAINT ck_buses_registration_not_blank CHECK (length(btrim(registration_number)) > 0)
);
CREATE INDEX ix_buses_operator_status ON buses (operator_id, status);
CREATE INDEX ix_buses_bus_type_id ON buses (bus_type_id);
CREATE INDEX ix_buses_seat_layout_id ON buses (seat_layout_id);

CREATE TABLE locations (
    id UUID PRIMARY KEY,
    country_code CHAR(2) NOT NULL DEFAULT 'IN',
    state VARCHAR(120) NOT NULL,
    district VARCHAR(120),
    city VARCHAR(120) NOT NULL,
    locality VARCHAR(160),
    latitude NUMERIC(9,6),
    longitude NUMERIC(9,6),
    time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_locations_state_not_blank CHECK (length(btrim(state)) > 0),
    CONSTRAINT ck_locations_city_not_blank CHECK (length(btrim(city)) > 0),
    CONSTRAINT ck_locations_latitude CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_locations_longitude CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);
CREATE INDEX ix_locations_country_state_city ON locations (country_code, state, city);

CREATE TABLE routes (
    id UUID PRIMARY KEY,
    operator_id UUID NOT NULL REFERENCES operators(id),
    code VARCHAR(60) NOT NULL,
    name VARCHAR(160) NOT NULL,
    source_location_id UUID NOT NULL REFERENCES locations(id),
    destination_location_id UUID NOT NULL REFERENCES locations(id),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_routes_operator_code UNIQUE (operator_id, code),
    CONSTRAINT ck_routes_different_endpoints CHECK (source_location_id <> destination_location_id),
    CONSTRAINT ck_routes_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_routes_code_not_blank CHECK (length(btrim(code)) > 0),
    CONSTRAINT ck_routes_name_not_blank CHECK (length(btrim(name)) > 0)
);
CREATE INDEX ix_routes_operator_status ON routes (operator_id, status);
CREATE INDEX ix_routes_source_location_id ON routes (source_location_id);
CREATE INDEX ix_routes_destination_location_id ON routes (destination_location_id);

CREATE TABLE route_stops (
    id UUID PRIMARY KEY,
    route_id UUID NOT NULL REFERENCES routes(id) ON DELETE CASCADE,
    location_id UUID NOT NULL REFERENCES locations(id),
    sequence_number INTEGER NOT NULL,
    arrival_offset_minutes INTEGER,
    departure_offset_minutes INTEGER,
    distance_km NUMERIC(10,2),
    stop_kind VARCHAR(30) NOT NULL DEFAULT 'INTERMEDIATE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_route_stops_route_sequence UNIQUE (route_id, sequence_number),
    CONSTRAINT ck_route_stops_sequence CHECK (sequence_number > 0),
    CONSTRAINT ck_route_stops_offsets CHECK (
        (arrival_offset_minutes IS NULL OR arrival_offset_minutes >= 0)
        AND (departure_offset_minutes IS NULL OR departure_offset_minutes >= 0)
        AND (arrival_offset_minutes IS NULL OR departure_offset_minutes IS NULL
            OR departure_offset_minutes >= arrival_offset_minutes)
    ),
    CONSTRAINT ck_route_stops_distance CHECK (distance_km IS NULL OR distance_km >= 0),
    CONSTRAINT ck_route_stops_kind CHECK (stop_kind IN ('SOURCE', 'INTERMEDIATE', 'DESTINATION'))
);
CREATE INDEX ix_route_stops_route_location ON route_stops (route_id, location_id);

