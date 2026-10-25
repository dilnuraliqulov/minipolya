--liquibase formatted sql

--changeset minipolya:023-stadium-min-booking-amount
--comment: Per-stadium minimum up-front payment, in tiyin. NULL means the player must pay in full. The floor is 50 000 tiyin (500 so'm), the smallest amount ATMOS will take.
ALTER TABLE stadiums
    ADD COLUMN min_booking_amount BIGINT;

ALTER TABLE stadiums
    ADD CONSTRAINT chk_stadiums_min_booking_amount
        CHECK (min_booking_amount IS NULL OR min_booking_amount >= 50000);

--changeset minipolya:023-booking-payment-status
--comment: How much of a booking has been paid. paid_amount is the running total of its successful payments; payment_status is derived from it but stored so it can be filtered and indexed.
ALTER TABLE bookings
    ADD COLUMN paid_amount BIGINT NOT NULL DEFAULT 0;

ALTER TABLE bookings
    ADD COLUMN payment_status VARCHAR(20) NOT NULL DEFAULT 'unpaid'
        CHECK (payment_status IN ('unpaid', 'partially_paid', 'paid'));

-- Bookings that were already confirmed before this changeset were paid in full
-- under the old all-or-nothing rule.
UPDATE bookings
SET paid_amount = price, payment_status = 'paid'
WHERE status IN ('confirmed', 'completed');

CREATE INDEX idx_bookings_payment_status ON bookings (payment_status);

--changeset minipolya:023-payment-split-and-method
--comment: Each payment records the owner/platform split, the commission percent used at the time, and whether the money moved by card or cash. Existing rows predate the commission, so they are all owner money.
ALTER TABLE payments
    ADD COLUMN owner_amount BIGINT;
ALTER TABLE payments
    ADD COLUMN platform_amount BIGINT;
ALTER TABLE payments
    ADD COLUMN commission_percent INTEGER;
ALTER TABLE payments
    ADD COLUMN method VARCHAR(20);

UPDATE payments
SET owner_amount = amount,
    platform_amount = 0,
    commission_percent = 0,
    method = CASE WHEN provider = 'cash' THEN 'cash' ELSE 'card' END;

ALTER TABLE payments ALTER COLUMN owner_amount SET NOT NULL;
ALTER TABLE payments ALTER COLUMN platform_amount SET NOT NULL;
ALTER TABLE payments ALTER COLUMN commission_percent SET NOT NULL;
ALTER TABLE payments ALTER COLUMN method SET NOT NULL;

ALTER TABLE payments
    ADD CONSTRAINT chk_payments_method CHECK (method IN ('card', 'cash'));

-- The split must always account for the whole amount, refunds (negative) included.
ALTER TABLE payments
    ADD CONSTRAINT chk_payments_split CHECK (owner_amount + platform_amount = amount);

ALTER TABLE payments
    ADD CONSTRAINT chk_payments_commission_percent CHECK (commission_percent BETWEEN 0 AND 50);

--changeset minipolya:023-payment-types-and-statuses
--comment: Adds the ATMOS provider, the remainder and team_game payment types, and the hold lifecycle statuses. 'pending' and 'completed' stay in the CHECK only because the legacy Payme/Click game flow still writes them; they go when that flow does.
ALTER TABLE payments DROP CONSTRAINT payments_provider_check;
ALTER TABLE payments ADD CONSTRAINT payments_provider_check
    CHECK (provider IN ('atmos', 'payme', 'click', 'cash'));

ALTER TABLE payments DROP CONSTRAINT payments_payment_type_check;
ALTER TABLE payments ADD CONSTRAINT payments_payment_type_check
    CHECK (payment_type IN ('booking', 'remainder', 'team_game', 'game_share', 'service_fee', 'refund'));

ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
    CHECK (status IN ('held', 'captured', 'released', 'paid', 'refunded', 'failed',
                      'pending', 'completed'));
