--liquibase formatted sql

--changeset minipolya:040-drop-booking-commission
--comment: The platform takes nothing from ordinary bookings: every booking card payment is the owner's in full. The booking commission setting goes; existing payments keep their stored commission_percent and splits.
ALTER TABLE platform_settings DROP COLUMN booking_commission_percent;

--changeset minipolya:040-booking-pricing-model
--comment: How a booking counts its money. 'player_price' (every booking from now on): the total is the player price (card_price; the owner's own price on a manual booking) and paid_amount counts what the player paid, card or cash, so the remainder is the same amount in the app and in cash. 'net' (bookings created before this change, and game bookings, whose teams' debts live on game_teams): price and paid_amount in the owner's terms, as before. Stored amounts are not touched.
ALTER TABLE bookings
    ADD COLUMN pricing_model VARCHAR(20) NOT NULL DEFAULT 'player_price'
        CONSTRAINT chk_bookings_pricing_model CHECK (pricing_model IN ('net', 'player_price'));
UPDATE bookings SET pricing_model = 'net';
