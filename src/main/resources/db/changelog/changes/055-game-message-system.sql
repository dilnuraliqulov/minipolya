--liquibase formatted sql

--changeset minipolya:055-game-message-system
--comment: System lines in a team game's chat — "the game is confirmed", "<team> joined" — written by the platform, not by a player. They are ordinary game_messages rows so they page and sort with everything else and a team that joined later still sees only what came after it; kind tells them apart, event says which one it is, and event_team_name carries the team a line is about so the clients can word it in the reader's own language. user_id becomes nullable because nobody sent them. Existing rows are all 'user'.
ALTER TABLE game_messages
    ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE game_messages
    ADD COLUMN kind            VARCHAR(10) NOT NULL DEFAULT 'user',
    ADD COLUMN event           VARCHAR(40),
    ADD COLUMN event_team_name VARCHAR(100);

ALTER TABLE game_messages
    ADD CONSTRAINT chk_game_messages_kind CHECK (kind IN ('user', 'system'));

-- A player's message has a sender; a system line has none and says which event it is.
ALTER TABLE game_messages
    ADD CONSTRAINT chk_game_messages_sender CHECK (
        (kind = 'user' AND user_id IS NOT NULL)
        OR (kind = 'system' AND user_id IS NULL AND event IS NOT NULL));
