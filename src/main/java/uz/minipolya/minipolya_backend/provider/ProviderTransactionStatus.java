package uz.minipolya.minipolya_backend.provider;

/**
 * Where a provider transaction stands. A charge lands on {@code PAID}; a hold
 * lands on {@code HELD} and then moves forward exactly once, to
 * {@code CAPTURED} or {@code RELEASED}. {@code REFUNDED} always means a full
 * refund — ATMOS cannot refund part of a transaction.
 */
public enum ProviderTransactionStatus {
    HELD,
    CAPTURED,
    RELEASED,
    PAID,
    REFUNDED,
    FAILED
}
