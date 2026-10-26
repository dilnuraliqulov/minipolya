--liquibase formatted sql

--changeset minipolya:019-users-is-blocked
--comment: Add users.is_blocked so an admin can block a user from the platform
ALTER TABLE users ADD COLUMN is_blocked BOOLEAN NOT NULL DEFAULT false;
