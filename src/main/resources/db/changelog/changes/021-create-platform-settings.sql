--liquibase formatted sql

--changeset minipolya:021-create-platform-settings
--comment: Single-row table of platform-wide money settings, editable by admins. The singleton column makes "exactly one row" a database fact. All fees are tiyin.
CREATE TABLE platform_settings (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    singleton                  BOOLEAN NOT NULL DEFAULT TRUE,
    booking_commission_percent INTEGER NOT NULL DEFAULT 0
                                   CHECK (booking_commission_percent BETWEEN 0 AND 50),
    team_game_platform_fee     BIGINT NOT NULL DEFAULT 2500000
                                   CHECK (team_game_platform_fee >= 0),
    casual_game_service_fee    BIGINT NOT NULL DEFAULT 0
                                   CHECK (casual_game_service_fee >= 0),
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_platform_settings_singleton UNIQUE (singleton),
    CONSTRAINT chk_platform_settings_singleton CHECK (singleton)
);

--changeset minipolya:021-seed-platform-settings
--comment: Seed the one row with the agreed defaults: no booking commission, 25 000 so'm team game fee, no casual game service fee.
INSERT INTO platform_settings (booking_commission_percent, team_game_platform_fee, casual_game_service_fee)
VALUES (0, 2500000, 0);

--changeset minipolya:021-platform-settings-updated-at
--comment: Reuse set_updated_at() (changeset 017) so updated_at tracks admin edits
CREATE TRIGGER trg_platform_settings_updated_at
    BEFORE UPDATE ON platform_settings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
