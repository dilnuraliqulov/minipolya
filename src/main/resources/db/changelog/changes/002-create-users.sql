--liquibase formatted sql

--changeset minipolya:002-create-users
--comment: Create users table — players (default), owners and admins (roles are boolean flags)
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone       VARCHAR(20) UNIQUE,
    name        VARCHAR(120) NOT NULL,
    telegram_id BIGINT UNIQUE,
    language    VARCHAR(8) NOT NULL DEFAULT 'uz',
    is_owner    BOOLEAN NOT NULL DEFAULT FALSE,
    is_admin    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_telegram_id ON users (telegram_id);
CREATE INDEX idx_users_phone ON users (phone);
