--liquibase formatted sql

--changeset minipolya:052-game-team-payment-option
--comment: T5: a team's chosen payment (minimum / full; NULL on rows from before, which keep their stored amounts) and the player amounts it fixed: join_price (paid or held on joining) and remainder_price (still owed: the full player amount less join_price). Tiyin.
ALTER TABLE game_teams
    ADD COLUMN payment_option VARCHAR(10)
        CONSTRAINT chk_game_teams_payment_option CHECK (payment_option IN ('minimum', 'full'));
ALTER TABLE game_teams ADD COLUMN join_price BIGINT;
ALTER TABLE game_teams ADD COLUMN remainder_price BIGINT;

--changeset minipolya:052-game-team-remainder-marks
--comment: T10: the owner's mark on a team's remainder, as on bookings (changeset 050): cash_received or not_paid, when and by whom. Null = no mark.
ALTER TABLE game_teams
    ADD COLUMN remainder_mark VARCHAR(20)
        CONSTRAINT chk_game_teams_remainder_mark CHECK (remainder_mark IN ('cash_received', 'not_paid'));
ALTER TABLE game_teams ADD COLUMN remainder_marked_at TIMESTAMPTZ;
ALTER TABLE game_teams ADD COLUMN remainder_marked_by UUID;
