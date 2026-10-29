--liquibase formatted sql

--changeset minipolya:039-stadium-notes
--comment: Short internal notes admins keep while reviewing a stadium. Only admins read them; owners and players never do. author_name is the admin's name when they wrote it. Notes are never edited or deleted.
CREATE TABLE stadium_notes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stadium_id  UUID NOT NULL REFERENCES stadiums (id),
    author_id   UUID NOT NULL REFERENCES users (id),
    author_name VARCHAR(120) NOT NULL,
    text        VARCHAR(1000) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_stadium_notes_stadium_created ON stadium_notes (stadium_id, created_at DESC);
