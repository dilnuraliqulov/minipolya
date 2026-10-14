--liquibase formatted sql

--changeset minipolya:011-create-game-messages
--comment: Simple REST (polling) chat for team_vs_team games — one row per message.
CREATE TABLE game_messages (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id    UUID NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES users (id),
    message    TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_game_messages_game_id ON game_messages (game_id);
