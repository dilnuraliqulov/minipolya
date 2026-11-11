--liquibase formatted sql

--changeset minipolya:046-game-team-joining
--comment: A team joining a team game is 'joining' until the waiting team's hold is captured: the join's steps (pending booking, the joiner's charge, the capture) each commit on their own, so a crash or an unclear answer half-way leaves a record the reconcile job finishes (confirms the game, or undoes the join and gives the money back). A joining team holds its side like an active one, so a second opponent cannot take it meanwhile.
ALTER TABLE game_teams DROP CONSTRAINT IF EXISTS game_teams_status_check;
ALTER TABLE game_teams DROP CONSTRAINT IF EXISTS chk_game_teams_status;
ALTER TABLE game_teams ADD CONSTRAINT chk_game_teams_status CHECK (status IN ('active', 'joining', 'cancelled'));
DROP INDEX IF EXISTS uq_game_teams_active_side;
CREATE UNIQUE INDEX uq_game_teams_active_side ON game_teams (game_id, side) WHERE status IN ('active', 'joining');
CREATE INDEX IF NOT EXISTS idx_game_teams_joining ON game_teams (joined_at) WHERE status = 'joining';
