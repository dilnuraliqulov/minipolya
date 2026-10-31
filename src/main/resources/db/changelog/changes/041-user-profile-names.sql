--liquibase formatted sql

--changeset minipolya:041-user-profile-names
--comment: A player's first and last name, entered only by the player (PATCH /api/users/me), never copied from Telegram. Null until they fill in their profile. users.name stays the display name every screen uses; setting either part rewrites it as "first last".
ALTER TABLE users ADD COLUMN first_name VARCHAR(60);
ALTER TABLE users ADD COLUMN last_name  VARCHAR(60);
