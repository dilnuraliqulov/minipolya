--liquibase formatted sql

--changeset minipolya:051-default-min-booking-amount
--comment: T1: the platform's default minimum booking amount (tiyin), used by every stadium without a minimum of its own (or with one not above the team-game fee). 5 000 000 tiyin = 50 000 so'm. Must stay above team_game_platform_fee (checked by the settings service).
ALTER TABLE platform_settings
    ADD COLUMN default_min_booking_amount BIGINT NOT NULL DEFAULT 5000000
        CONSTRAINT chk_platform_settings_default_min_booking_amount CHECK (default_min_booking_amount > 0);
