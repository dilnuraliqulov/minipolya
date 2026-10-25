--liquibase formatted sql

--changeset minipolya:025-create-game-teams
--comment: One row per team occupying a side of a team-vs-team game. This is the unit a team's money hangs off: its platform fee, its owner advance and its remainder. A team that leaves is kept as 'cancelled' so its payments stay explainable, and the next team on that side gets a fresh row.
CREATE TABLE game_teams (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id          UUID NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    team_id          UUID NOT NULL REFERENCES teams (id),
    leader_id        UUID NOT NULL REFERENCES users (id),
    side             VARCHAR(1) NOT NULL CHECK (side IN ('a', 'b')),
    status           VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'cancelled')),
    fee_amount       BIGINT NOT NULL CHECK (fee_amount >= 0),
    advance_amount   BIGINT NOT NULL CHECK (advance_amount >= 0),
    remainder_amount BIGINT NOT NULL CHECK (remainder_amount >= 0),
    remainder_status VARCHAR(20) NOT NULL DEFAULT 'unpaid' CHECK (remainder_status IN ('unpaid', 'paid')),
    joined_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at          TIMESTAMPTZ
);

-- At most one live occupant per side. Past occupants keep their rows, so a
-- reopened game can take a new opponent without losing who paid what before.
CREATE UNIQUE INDEX uq_game_teams_active_side ON game_teams (game_id, side) WHERE status = 'active';

CREATE INDEX idx_game_teams_game_id ON game_teams (game_id);
CREATE INDEX idx_game_teams_team_id ON game_teams (team_id);

--changeset minipolya:025-payment-game-team
--comment: A team game's payments belong to the game AND the team, not only to a booking: a booking comes and goes as opponents join and leave, while the remaining team's payments stay attached to its game_teams row.
ALTER TABLE payments
    ADD COLUMN game_team_id UUID REFERENCES game_teams (id);

CREATE INDEX idx_payments_game_team_id ON payments (game_team_id);

--changeset minipolya:025-drop-creator-share-pct
--comment: Team games no longer split the pitch price by a percentage. Each team pays the platform fee plus half the stadium minimum up front and half the rest later, so creator_share_pct has no meaning.
ALTER TABLE games DROP COLUMN creator_share_pct;
