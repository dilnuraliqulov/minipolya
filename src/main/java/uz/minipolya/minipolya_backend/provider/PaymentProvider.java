package uz.minipolya.minipolya_backend.provider;

/**
 * The card provider every new payment flow goes through (ATMOS in production,
 * {@link FakePaymentProvider} until its API docs land).
 *
 * <p>A card is bound once — {@link #startCardBinding} then
 * {@link #confirmCardBinding} with the SMS code — and afterwards charged by
 * token. The full card number is passed straight to the provider and never
 * stored or logged on our side.
 *
 * <p>Money moves in one of two shapes. A {@link #charge} takes it immediately.
 * A {@link #hold} reserves it and is then settled exactly once, with
 * {@link #capture} or {@link #release}. Every amount is tiyin, and every
 * {@link #refund} is for the whole transaction: ATMOS cannot refund part of one.
 *
 * <p>A declined card comes back as a {@code FAILED}
 * {@link ProviderTransaction} rather than an exception — callers are expected
 * to handle that outcome. Malformed input (an unknown binding or transaction, a
 * wrong SMS code, a hold settled twice) throws instead.
 */
public interface PaymentProvider {

    /**
     * Begins binding a card. The number and expiry are passed through to the
     * provider and kept nowhere on our side.
     *
     * @param cardNumber the full card number — transient, never persisted or logged
     * @param expiry     expiry as {@code MM/YY}
     * @return the provider's handle for the pending binding
     */
    CardBinding startCardBinding(String cardNumber, String expiry);

    /**
     * Completes a binding with the SMS code the provider sent the cardholder.
     *
     * @return the token and masked number — all we are allowed to keep
     */
    BoundCard confirmCardBinding(String bindingId, String smsCode);

    /** Takes the money now. */
    ProviderTransaction charge(ChargeCommand command);

    /** Reserves the money, to be captured or released later. */
    ProviderTransaction hold(ChargeCommand command);

    /** Settles a hold by taking the reserved money. */
    ProviderTransaction capture(String providerTxnId);

    /** Settles a hold by giving the reserved money back. */
    ProviderTransaction release(String providerTxnId);

    /** Refunds a transaction in full. Partial refunds are not supported. */
    ProviderTransaction refund(String providerTxnId);
}
