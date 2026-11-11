--liquibase formatted sql

--changeset minipolya:048-stadium-wizard-fields
--comment: The add-stadium wizard: stadiums.landmark ("Mo'ljal") and four more amenities
ALTER TABLE stadiums ADD COLUMN landmark VARCHAR(150);

ALTER TABLE stadium_amenities ADD COLUMN toilet         BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE stadium_amenities ADD COLUMN covered        BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE stadium_amenities ADD COLUMN bibs           BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE stadium_amenities ADD COLUMN drinking_water BOOLEAN NOT NULL DEFAULT FALSE;
