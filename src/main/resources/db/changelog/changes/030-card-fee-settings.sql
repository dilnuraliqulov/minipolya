--liquibase formatted sql

--changeset minipolya:030-card-fee-settings
--comment: The card fee ATMOS takes from every card payment, in basis points (150 = 1.5%), and the step player card prices are rounded up to (10 000 tiyin = 100 so'm). Player card prices are grossed up by the fee so the owner and the platform still net their full amounts. Cash is never grossed up.
ALTER TABLE platform_settings
    ADD COLUMN card_fee_bps INTEGER NOT NULL DEFAULT 150
        CHECK (card_fee_bps BETWEEN 0 AND 1000);
ALTER TABLE platform_settings
    ADD COLUMN payment_rounding_step BIGINT NOT NULL DEFAULT 10000
        CHECK (payment_rounding_step BETWEEN 1 AND 1000000);
