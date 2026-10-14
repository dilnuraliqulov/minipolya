--liquibase formatted sql

--changeset minipolya:007-create-games
--comment: Create games table (casual, open, team_vs_team). booking_id FK is added in step 8 once the bookings table exists.
CREATE TABLE games (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    creator_id             UUID NOT NULL REFERENCES users (id),
    stadium_id             UUID NOT NULL REFERENCES stadiums (id) ON DELETE CASCADE,
    booking_id             UUID,
    type                   VARCHAR(20) NOT NULL CHECK (type IN ('casual', 'open', 'team_vs_team')),
    status                 VARCHAR(20) NOT NULL DEFAULT 'open'
                               CHECK (status IN ('open', 'confirmed', 'completed',
                                                 'cancelled_slot_taken', 'cancelled_by_creator', 'cancelled_not_filled')),
    title                  VARCHAR(200),
    description            TEXT,
    max_players            INTEGER NOT NULL CHECK (max_players > 0),
    min_players            INTEGER,
    starts_at              TIMESTAMPTZ NOT NULL,
    ends_at                TIMESTAMPTZ NOT NULL,
    total_price            INTEGER NOT NULL CHECK (total_price >= 0),
    service_fee_per_person INTEGER NOT NULL DEFAULT 5000 CHECK (service_fee_per_person >= 0),
    creator_share_pct      INTEGER NOT NULL DEFAULT 50 CHECK (creator_share_pct BETWEEN 1 AND 100),
    team_a_id              UUID REFERENCES teams (id),
    team_b_id              UUID REFERENCES teams (id),
    is_public              BOOLEAN NOT NULL DEFAULT FALSE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_games_time_order CHECK (starts_at < ends_at)
);

CREATE INDEX idx_games_stadium_id ON games (stadium_id);
CREATE INDEX idx_games_creator_id ON games (creator_id);
CREATE INDEX idx_games_status ON games (status);
CREATE INDEX idx_games_starts_at ON games (starts_at);
