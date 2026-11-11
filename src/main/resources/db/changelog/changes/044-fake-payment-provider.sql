--liquibase formatted sql

--changeset minipolya:044-fake-payment-provider
--comment: FakePaymentProvider's own state (dev and tests only), so fake cards, charges and holds survive a restart: bindings, cards with their balances, charges and holds with one row per split, and every reverse asked. Like a real provider's records, no foreign key points at our own tables. Created everywhere so Hibernate's validate sees the same schema in every profile, but nothing reads or writes them unless app.payments.provider=fake (never under atmos or prod). Card numbers, SMS codes and tokens are never stored: a card is its masked number, last four digits and the SHA-256 of its token.
CREATE TABLE fake_payment_bindings (
    binding_id   VARCHAR(64)  PRIMARY KEY,
    masked_pan   VARCHAR(32)  NOT NULL,
    last4        VARCHAR(4)   NOT NULL,
    expiry       VARCHAR(5)   NOT NULL,
    masked_phone VARCHAR(32)  NOT NULL,
    card_holder  VARCHAR(100) NOT NULL,
    code_sent_at TIMESTAMPTZ  NOT NULL,
    confirmed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE fake_payment_cards (
    card_id      VARCHAR(64)  PRIMARY KEY,
    token_hash   CHAR(64)     NOT NULL,
    masked_pan   VARCHAR(32)  NOT NULL,
    last4        VARCHAR(4)   NOT NULL,
    expiry       VARCHAR(5)   NOT NULL,
    card_holder  VARCHAR(100) NOT NULL,
    masked_phone VARCHAR(32)  NOT NULL,
    balance      BIGINT       NOT NULL,
    is_active    BOOLEAN      NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    removed_at   TIMESTAMPTZ,
    CONSTRAINT uq_fake_payment_cards_token UNIQUE (token_hash),
    CONSTRAINT chk_fake_payment_cards_balance CHECK (balance >= 0)
);
CREATE INDEX idx_fake_payment_cards_created_at ON fake_payment_cards (created_at DESC);

CREATE TABLE fake_payment_operations (
    id           VARCHAR(64) PRIMARY KEY,
    kind         VARCHAR(10) NOT NULL,
    account      VARCHAR(64) NOT NULL,
    card_id      VARCHAR(64),
    last4        VARCHAR(4)  NOT NULL,
    otp_required BOOLEAN     NOT NULL,
    amount       BIGINT      NOT NULL,
    commission   BIGINT      NOT NULL,
    status       VARCHAR(16) NOT NULL,
    error_code   VARCHAR(40),
    hold_till    TIMESTAMPTZ,
    succeeded_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_fake_payment_operations_account UNIQUE (kind, account),
    CONSTRAINT chk_fake_payment_operations_kind CHECK (kind IN ('CHARGE', 'HOLD'))
);
CREATE INDEX idx_fake_payment_operations_created_at ON fake_payment_operations (kind, created_at DESC);

CREATE TABLE fake_payment_splits (
    transaction_id   VARCHAR(64) PRIMARY KEY,
    operation_id     VARCHAR(64) NOT NULL REFERENCES fake_payment_operations (id),
    split_index      INT         NOT NULL,
    recipient_id     VARCHAR(50),
    is_platform      BOOLEAN     NOT NULL,
    amount           BIGINT      NOT NULL,
    commission       BIGINT      NOT NULL,
    success_trans_id VARCHAR(80),
    reversed_at      TIMESTAMPTZ,
    CONSTRAINT uq_fake_payment_splits_success UNIQUE (success_trans_id)
);
CREATE INDEX idx_fake_payment_splits_operation ON fake_payment_splits (operation_id);

CREATE TABLE fake_payment_reverses (
    id             VARCHAR(64)  PRIMARY KEY,
    operation_id   VARCHAR(64)  NOT NULL,
    transaction_id VARCHAR(80)  NOT NULL,
    amount         BIGINT       NOT NULL,
    outcome        VARCHAR(10)  NOT NULL,
    error_code     VARCHAR(40),
    reason         VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_fake_payment_reverses_outcome CHECK (outcome IN ('REVERSED', 'REFUSED'))
);
CREATE INDEX idx_fake_payment_reverses_created_at ON fake_payment_reverses (created_at DESC);
