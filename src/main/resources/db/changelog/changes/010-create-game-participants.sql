--liquibase formatted sql

--changeset minipolya:010-create-game-participants
--comment: Players in a game. One row per (game, user); side is assigned on confirm; payment_id links a paid participant's share (null for non-paying team members).
CREATE TABLE game_participants (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id    UUID NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES users (id),
    side       VARCHAR(1) CHECK (side IN ('a', 'b')),
    payment_id UUID REFERENCES payments (id),
    status     VARCHAR(20) NOT NULL DEFAULT 'joined' CHECK (status IN ('joined', 'paid', 'cancelled', 'refunded')),
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_game_participant UNIQUE (game_id, user_id)
);

CREATE INDEX idx_game_participants_game_id ON game_participants (game_id);
CREATE INDEX idx_game_participants_user_id ON game_participants (user_id);
