--liquibase formatted sql

--changeset minipolya:050-booking-remainder-marks
--comment: The owner's mark on a booking's remainder (E5): cash_received (the owner took the rest in cash) or not_paid (the player did not pay it; no charge, no penalty). When and by whom it was set, for the owner's same-day undo and the audit trail. Null = no mark.
ALTER TABLE bookings
    ADD COLUMN remainder_mark VARCHAR(20)
        CONSTRAINT chk_bookings_remainder_mark CHECK (remainder_mark IN ('cash_received', 'not_paid'));
ALTER TABLE bookings ADD COLUMN remainder_marked_at TIMESTAMPTZ;
ALTER TABLE bookings ADD COLUMN remainder_marked_by UUID;

--changeset minipolya:050-payments-cancelled-status
--comment: A cash row voided by undoing a "cash received" mark becomes 'cancelled' (never a platform refund: no money moved through the platform). It no longer counts anywhere money that arrived is summed.
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
    CHECK (status IN ('processing', 'held', 'captured', 'released', 'paid', 'refunded', 'failed',
                      'return_pending', 'capture_pending', 'refund_failed', 'pending', 'completed',
                      'cancelled'));
