--liquibase formatted sql

--changeset minipolya:018-booking-duration-unbounded
--comment: Allow bookings and recurring series of any whole-hour duration (>= 1); was limited to 1 or 2 hours
ALTER TABLE bookings DROP CONSTRAINT bookings_duration_hours_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_duration_hours_check CHECK (duration_hours >= 1);

ALTER TABLE booking_series DROP CONSTRAINT booking_series_duration_hours_check;
ALTER TABLE booking_series ADD CONSTRAINT booking_series_duration_hours_check CHECK (duration_hours >= 1);
