--liquibase formatted sql

--changeset minipolya:043-team-invite-code
--comment: R32 team invite links: a random URL-safe code per team (created on first request, replaced on renewal), unique; null until the leader first asks for one.
ALTER TABLE teams ADD COLUMN invite_code VARCHAR(32);
ALTER TABLE teams ADD CONSTRAINT uq_teams_invite_code UNIQUE (invite_code);
