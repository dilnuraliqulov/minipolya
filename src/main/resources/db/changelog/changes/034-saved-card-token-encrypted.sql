--liquibase formatted sql

--changeset minipolya:034-saved-card-token-encrypted
--comment: provider_token now holds the token encrypted with AES-256-GCM (v1: prefix, base64 of IV, ciphertext and tag), which is longer than the token itself. Rows written before keep their plain token until next written; the application reads both.
ALTER TABLE saved_cards ALTER COLUMN provider_token TYPE VARCHAR(512);
