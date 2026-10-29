--liquibase formatted sql

--changeset minipolya:035-payments-refund-failures
--comment: A refund the provider declines is no longer rolled back with the cancellation: its refund row is saved 'failed' with the provider's error in failure_reason, and the payment it was to give back becomes 'refund_failed' (the money is still owed). An admin retries it, or marks it refunded by hand when the money went back outside the platform (refunded_manually, manual_refund_reason, manual_refund_by). The partial index serves the failed-refund count and list.
ALTER TABLE payments ADD COLUMN failure_reason VARCHAR(500);
ALTER TABLE payments ADD COLUMN refunded_manually BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE payments ADD COLUMN manual_refund_reason VARCHAR(500);
ALTER TABLE payments ADD COLUMN manual_refund_by UUID REFERENCES users (id);
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
    CHECK (status IN ('processing', 'held', 'captured', 'released', 'paid', 'refunded', 'failed',
                      'return_pending', 'capture_pending', 'refund_failed', 'pending', 'completed'));
CREATE INDEX idx_payments_failed_refunds ON payments (created_at)
    WHERE payment_type = 'refund' AND status = 'failed';
