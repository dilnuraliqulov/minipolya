--liquibase formatted sql

--changeset minipolya:027-pgcrypto
--comment: pgcrypto lets the platform owner create an admin with plain SQL, hashing the password in the database with crypt(..., gen_salt('bf', 12)). The application verifies those hashes with Spring's BCryptPasswordEncoder.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

--changeset minipolya:027-users-admin-credentials
--comment: Username + password sign-in for platform admins. Only admins have a username and password_hash; players and owners keep signing in through Telegram. Usernames are unique case-insensitively. failed_login_attempts and locked_until drive the lockout after repeated wrong passwords; last_login_at records the latest successful password sign-in. telegram_id and phone are already nullable, so an admin needs no Telegram account.
ALTER TABLE users ADD COLUMN username              VARCHAR(64);
ALTER TABLE users ADD COLUMN password_hash         VARCHAR(100);
ALTER TABLE users ADD COLUMN failed_login_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until          TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN last_login_at         TIMESTAMPTZ;
CREATE UNIQUE INDEX uq_users_username_lower ON users (LOWER(username));

--changeset minipolya:027-audit-log-entity-id-nullable
--comment: A failed admin sign-in with an unknown username concerns no stored entity, yet it must still be audited.
ALTER TABLE audit_log ALTER COLUMN entity_id DROP NOT NULL;
