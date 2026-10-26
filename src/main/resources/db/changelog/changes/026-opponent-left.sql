--liquibase formatted sql

--changeset minipolya:026-booking-opponent-left
--comment: A team game's booking is cancelled when one team pulls out, and the slot goes back on sale while the other team waits for a new opponent. The owner's schedule needs to say so, rather than showing a booking the player or the owner apparently cancelled. The EXCLUDE constraint only covers 'pending' and 'confirmed', so the freed slot needs no further change.
ALTER TABLE bookings DROP CONSTRAINT bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check
    CHECK (status IN ('pending', 'confirmed', 'completed', 'expired',
                      'cancelled_by_user', 'cancelled_by_owner', 'cancelled_opponent_left'));

--changeset minipolya:026-notification-opponent-left
--comment: The remaining team is told its opponent left and the game is looking for a new one, which is not the same message as "the game is cancelled".
ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN (
        'booking_confirmed', 'booking_cancelled', 'booking_reminder', 'booking_expired',
        'game_confirmed', 'game_cancelled', 'game_player_joined', 'game_player_left',
        'game_payment_received', 'game_opponent_left', 'review_reply',
        'stadium_approved', 'stadium_rejected',
        'owner_application_status', 'promotion_new'));
