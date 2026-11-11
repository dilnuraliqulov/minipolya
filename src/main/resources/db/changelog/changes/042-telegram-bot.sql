--liquibase formatted sql

--changeset minipolya:042-telegram-processed-updates
--comment: Telegram update ids already handled, so a redelivered update (webhook retry, polling restart) never runs twice. Rows older than a week are pruned by TelegramUpdateCleanupJob; Telegram keeps undelivered updates for 24 hours.
CREATE TABLE telegram_processed_updates (
    update_id   BIGINT      PRIMARY KEY,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_telegram_processed_updates_received_at ON telegram_processed_updates (received_at);

--changeset minipolya:042-telegram-bot-values
--comment: Small values the bot keeps between restarts, by key: e.g. banner_file_id, the file_id Telegram gave the /start banner on its first upload, so it is not uploaded again.
CREATE TABLE telegram_bot_values (
    key        VARCHAR(64) PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
