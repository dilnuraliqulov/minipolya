--liquibase formatted sql

--changeset minipolya:045-booking-status-length
--comment: bookings.status was VARCHAR(20), but 'cancelled_opponent_left' (changeset 026) is 23 characters: every cancellation of a confirmed team game by one of its leaders failed with a data error. Widened to 30; nothing else changes.
ALTER TABLE bookings ALTER COLUMN status TYPE VARCHAR(30);
