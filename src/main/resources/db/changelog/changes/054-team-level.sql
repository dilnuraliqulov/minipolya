--liquibase formatted sql

--changeset minipolya:054-team-level
--comment: A team's level — havaskor (friends), orta (regular) or professional (tournament). Chosen when the team is created and changed later only by its leader; shown wherever the team is, and never a bar to a game. Nullable on purpose: every team created before this changeset keeps no level, which the apps read as "not set".
ALTER TABLE teams
    ADD COLUMN level VARCHAR(20);

ALTER TABLE teams
    ADD CONSTRAINT chk_teams_level CHECK (level IN ('havaskor', 'orta', 'professional'));
