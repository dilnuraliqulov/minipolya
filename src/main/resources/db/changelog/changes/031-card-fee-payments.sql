--liquibase formatted sql

--changeset minipolya:031-payments-card-fee
--comment: amount stays the gross a card was charged (or the cash taken). card_fee_expected is what the card fee and the price rounding added on top of the net the payment covers, so amount - card_fee_expected is that net; it is 0 for cash and for rows written before the fee existed, and negative on a refund row like every other amount there. provider_commission is the commission_value the provider reports for the charge, NULL until it does. Amounts never change after insert.
ALTER TABLE payments ADD COLUMN card_fee_expected BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payments ADD COLUMN provider_commission BIGINT;

--changeset minipolya:031-bookings-card-price
--comment: What the player is quoted to pay by card for the whole booking: the sum of each hour's card price at the time it was booked, so later changes to the card fee settings never change a booking's price. price stays the owner's net price. NULL for bookings made before the card fee and for manual bookings, where it is worked out from price when shown.
ALTER TABLE bookings ADD COLUMN card_price BIGINT;
