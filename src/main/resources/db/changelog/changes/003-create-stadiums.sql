--liquibase formatted sql

--changeset minipolya:003-create-stadiums
--comment: Create stadiums table (1 stadium = 1 pitch) with per-stadium cancellation policy
CREATE TABLE stadiums (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id               UUID NOT NULL REFERENCES users (id),
    name                   VARCHAR(150) NOT NULL,
    address                VARCHAR(300) NOT NULL,
    latitude               NUMERIC(9, 6),
    longitude              NUMERIC(9, 6),
    phone                  VARCHAR(20),
    description            TEXT,
    format                 VARCHAR(10) NOT NULL CHECK (format IN ('5x5', '7x7', '9x9', '11x11')),
    surface_type           VARCHAR(20) NOT NULL CHECK (surface_type IN ('natural', 'artificial', 'hybrid')),
    open_time              TIME NOT NULL,
    close_time             TIME NOT NULL,
    price_per_hour         INTEGER NOT NULL CHECK (price_per_hour >= 0),
    status                 VARCHAR(20) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'blocked')),
    full_refund_hours      INTEGER NOT NULL DEFAULT 24,
    partial_refund_hours   INTEGER NOT NULL DEFAULT 12,
    partial_refund_percent INTEGER NOT NULL DEFAULT 50,
    is_active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_stadiums_owner_id ON stadiums (owner_id);
CREATE INDEX idx_stadiums_status ON stadiums (status);

--changeset minipolya:003-create-stadium-photos
--comment: Photos attached to a stadium, ordered by position
CREATE TABLE stadium_photos (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stadium_id UUID NOT NULL REFERENCES stadiums (id) ON DELETE CASCADE,
    url        VARCHAR(500) NOT NULL,
    position   INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_stadium_photos_stadium_id ON stadium_photos (stadium_id);

--changeset minipolya:003-create-stadium-amenities
--comment: One amenities row per stadium (boolean feature flags)
CREATE TABLE stadium_amenities (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stadium_id       UUID NOT NULL UNIQUE REFERENCES stadiums (id) ON DELETE CASCADE,
    parking          BOOLEAN NOT NULL DEFAULT FALSE,
    lighting         BOOLEAN NOT NULL DEFAULT FALSE,
    showers          BOOLEAN NOT NULL DEFAULT FALSE,
    locker_room      BOOLEAN NOT NULL DEFAULT FALSE,
    wifi             BOOLEAN NOT NULL DEFAULT FALSE,
    cafe             BOOLEAN NOT NULL DEFAULT FALSE,
    equipment_rental BOOLEAN NOT NULL DEFAULT FALSE,
    tribune          BOOLEAN NOT NULL DEFAULT FALSE
);
