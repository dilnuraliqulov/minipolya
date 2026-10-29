--liquibase formatted sql

--changeset minipolya:037-owner-legal-details
--comment: An owner's legal form, tax id (STIR, or PINFL for the self-employed) and ATMOS contract, kept by admins; one row per user. The ATMOS recipient id an owner's card splits are paid to now lives here and only here: it is moved out of users.atmos_recipient_id (changeset 032), whose owners are recorded as having a signed contract, and that column is dropped. A moved row has no legal type or tin until an admin enters them; otherwise the two come together, and the tin is 9 digits, or 14 for self_employed. A signed contract always has a recipient id.
CREATE TABLE owner_legal_details (
    user_id               UUID PRIMARY KEY REFERENCES users (id),
    legal_type            VARCHAR(20) CHECK (legal_type IN ('yatt', 'mchj', 'self_employed')),
    tin                   VARCHAR(14),
    atmos_contract_status VARCHAR(10) NOT NULL DEFAULT 'none'
        CHECK (atmos_contract_status IN ('none', 'pending', 'signed')),
    atmos_recipient_id    VARCHAR(64),
    updated_by            UUID REFERENCES users (id),
    updated_by_name       VARCHAR(120),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_owner_legal_type_with_tin CHECK ((legal_type IS NULL) = (tin IS NULL)),
    CONSTRAINT chk_owner_legal_tin CHECK (
        tin IS NULL
        OR (legal_type = 'self_employed' AND tin ~ '^[0-9]{14}$')
        OR (legal_type <> 'self_employed' AND tin ~ '^[0-9]{9}$')),
    CONSTRAINT chk_owner_legal_signed_recipient CHECK (
        atmos_contract_status <> 'signed' OR atmos_recipient_id IS NOT NULL)
);

INSERT INTO owner_legal_details (user_id, atmos_contract_status, atmos_recipient_id)
SELECT id, 'signed', atmos_recipient_id
FROM users
WHERE atmos_recipient_id IS NOT NULL;

ALTER TABLE users DROP COLUMN atmos_recipient_id;
