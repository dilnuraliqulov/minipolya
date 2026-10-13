--liquibase formatted sql

--changeset minipolya:001-extensions
--comment: Enable btree_gist extension for EXCLUDE constraints on bookings
CREATE EXTENSION IF NOT EXISTS btree_gist;
