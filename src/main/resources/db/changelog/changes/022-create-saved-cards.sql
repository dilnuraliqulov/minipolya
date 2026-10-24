--liquibase formatted sql

--changeset minipolya:022-create-saved-cards
--comment: Cards a player has bound with the payment provider. Only the provider token and a masked number are stored — never the card number itself. Deleting a card sets is_active = false so historical payments stay explainable.
CREATE TABLE saved_cards (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users (id),
    provider_token VARCHAR(255) NOT NULL,
    masked_number  VARCHAR(32) NOT NULL,
    card_type      VARCHAR(20),
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_saved_cards_provider_token UNIQUE (provider_token)
);

CREATE INDEX idx_saved_cards_user_id ON saved_cards (user_id);
CREATE INDEX idx_saved_cards_user_active ON saved_cards (user_id, is_active);
