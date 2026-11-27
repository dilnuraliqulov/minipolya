--liquibase formatted sql

--changeset minipolya:056-payment-needs-review
--comment: F6 — a hold whose capture or give-back has no clear outcome (ATMOS's GET /hold/{id} does not say whether a capture whose answer was lost went through) becomes 'needs_review' instead of being recorded as released: the money may have been taken. The reconcile job leaves it alone; an admin settles it by hand (captured, released or refunded). capture_requested_at is when a capture of the hold was first asked for: null means none ever was, so a hold ATMOS reports as gone was simply released.
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
    CHECK (status IN ('processing', 'held', 'captured', 'released', 'paid', 'refunded', 'failed',
                      'return_pending', 'capture_pending', 'refund_failed', 'pending', 'completed',
                      'cancelled', 'needs_review'));
ALTER TABLE payments ADD COLUMN capture_requested_at TIMESTAMPTZ;

--changeset minipolya:056-decline-reason-wrong-otp
--comment: F10 — STPIMS-ERR-098 ("OTP код указан неверно") at apply is a refusal with its own reason, WRONG_OTP, rather than a payment left processing. Nothing was taken.
ALTER TABLE payments DROP CONSTRAINT chk_payments_decline_reason;
ALTER TABLE payments ADD CONSTRAINT chk_payments_decline_reason
    CHECK (decline_reason IN ('INSUFFICIENT_FUNDS', 'CARD_EXPIRED', 'CARD_DECLINED', 'WRONG_OTP'));
