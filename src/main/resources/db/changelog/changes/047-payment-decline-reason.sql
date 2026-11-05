--liquibase formatted sql

--changeset minipolya:047-payment-decline-reason
--comment: R44 why a card payment was refused, in the player's terms (provider/DeclineReason maps the provider's code): INSUFFICIENT_FUNDS, CARD_EXPIRED or CARD_DECLINED. Set on a failed charge or hold the provider refused; null on every other row (and on a payment that failed without a refusal, e.g. one the provider never acknowledged). The provider's own code stays out of player responses.
ALTER TABLE payments ADD COLUMN decline_reason VARCHAR(30);
ALTER TABLE payments ADD CONSTRAINT chk_payments_decline_reason
    CHECK (decline_reason IN ('INSUFFICIENT_FUNDS', 'CARD_EXPIRED', 'CARD_DECLINED'));
