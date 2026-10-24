--liquibase formatted sql

--changeset minipolya:020-money-in-tiyin
--comment: All money becomes BIGINT tiyin (1 so'm = 100 tiyin); existing so'm values are multiplied by 100. promotions.discount_percent and games.creator_share_pct are percentages, not money, so they stay INTEGER.
ALTER TABLE stadiums
    ALTER COLUMN price_per_hour TYPE BIGINT USING price_per_hour::bigint * 100;

ALTER TABLE pricing_rules
    ALTER COLUMN price_per_hour TYPE BIGINT USING price_per_hour::bigint * 100;

ALTER TABLE bookings
    ALTER COLUMN price TYPE BIGINT USING price::bigint * 100;

ALTER TABLE payments
    ALTER COLUMN amount TYPE BIGINT USING amount::bigint * 100;

ALTER TABLE games
    ALTER COLUMN total_price TYPE BIGINT USING total_price::bigint * 100;

ALTER TABLE games
    ALTER COLUMN service_fee_per_person TYPE BIGINT USING service_fee_per_person::bigint * 100;

-- The old default was 5 000 so'm; in tiyin that is 500 000.
ALTER TABLE games
    ALTER COLUMN service_fee_per_person SET DEFAULT 500000;
