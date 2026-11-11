--liquibase formatted sql

--changeset minipolya:049-rounding-5000
--comment: Player prices are rounded up to 5 000 so'm (500 000 tiyin) instead of 100 so'm. Only the row still on the old default changes; a step an admin set on purpose is kept.
UPDATE platform_settings SET payment_rounding_step = 500000 WHERE payment_rounding_step = 10000;
ALTER TABLE platform_settings ALTER COLUMN payment_rounding_step SET DEFAULT 500000;
