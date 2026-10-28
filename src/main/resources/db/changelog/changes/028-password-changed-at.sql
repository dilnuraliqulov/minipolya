--liquibase formatted sql

--changeset minipolya:028-users-password-changed-at
--comment: When an admin's password last changed. JwtAuthFilter refuses any token issued before this moment, so changing (or resetting) a password signs out every older session.
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ;
