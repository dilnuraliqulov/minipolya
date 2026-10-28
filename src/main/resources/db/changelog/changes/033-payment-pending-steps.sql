--liquibase formatted sql

--changeset minipolya:033-payments-pending-steps
--comment: A multi-step money operation records each step on its payment row before calling the provider, so a step whose answer is lost can be finished by PaymentReconcileJob. return_pending - the money is being given back (a charge reversed, or a hold released, whichever its state calls for); capture_pending - a hold is being taken. Statuses still only move forward: return_pending to refunded or released, capture_pending to captured or released.
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
    CHECK (status IN ('processing', 'held', 'captured', 'released', 'paid', 'refunded', 'failed',
                      'return_pending', 'capture_pending', 'pending', 'completed'));
CREATE INDEX idx_payments_status_updated ON payments (status, updated_at);
