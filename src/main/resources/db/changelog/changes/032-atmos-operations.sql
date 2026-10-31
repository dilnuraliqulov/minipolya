--liquibase formatted sql

--changeset minipolya:032-payments-processing-status
--comment: A payment row is now written before the provider is called (its id is the provider "account", so a retry can never charge twice) and sits in 'processing' until the provider answers. A row whose answer was lost stays 'processing' for PaymentReconcileJob to settle; it is never assumed to have failed.
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
    CHECK (status IN ('processing', 'held', 'captured', 'released', 'paid', 'refunded', 'failed',
                      'pending', 'completed'));

--changeset minipolya:032-payments-provider-fields
--comment: A split payment is one provider transaction per recipient. provider_txn_id holds the transaction ids (comma-separated) or the hold id; provider_success_txn_ids the ids a confirmed charge returns, which are what a reverse needs. ofd_url is the fiscal receipt; hold_expires_at is when an unsettled hold lapses at the provider.
ALTER TABLE payments ADD COLUMN provider_success_txn_ids VARCHAR(500);
ALTER TABLE payments ADD COLUMN ofd_url VARCHAR(500);
ALTER TABLE payments ADD COLUMN hold_expires_at TIMESTAMPTZ;

--changeset minipolya:032-saved-cards-provider-card
--comment: The provider's own card id (needed to remove the card there) and the expiry the provider reports, shown to the player next to the masked number. The card number and the expiry the player typed are never stored.
ALTER TABLE saved_cards ADD COLUMN provider_card_id VARCHAR(64);
ALTER TABLE saved_cards ADD COLUMN expiry VARCHAR(5);

--changeset minipolya:032-users-atmos-recipient
--comment: The ATMOS recipient a stadium owner's split of every card payment is paid to. Set by the platform when the owner is onboarded with ATMOS.
ALTER TABLE users ADD COLUMN atmos_recipient_id VARCHAR(64);
