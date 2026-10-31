package uz.minipolya.minipolya_backend.provider;

import java.util.List;

/**
 * The card provider, shaped around the ATMOS operations ({@code AtmosPaymentProvider} in
 * production, {@link FakePaymentProvider} in dev and tests). Every amount is tiyin.
 *
 * <ul>
 *   <li><b>Cards</b>: {@link #bindCardInit} → SMS code → {@link #bindCardConfirm} gives a
 *       token; {@link #removeCard} unbinds it.</li>
 *   <li><b>Charges</b>: {@link #charge} creates one transaction per split;
 *       {@link #confirmCharge} takes the money (with the SMS code for a card number; a
 *       token needs none). {@link #getCharge} answers when a response was lost.
 *       {@link #reverse} gives a confirmed transaction back <em>in full</em> — there is no
 *       partial refund anywhere.</li>
 *   <li><b>Holds</b>: {@link #hold} → {@link #confirmHold} reserves the money for a
 *       duration; it is then settled once, by {@link #captureHold} (always the full
 *       amount) or {@link #releaseHold}. {@link #getHold} reports where it stands.</li>
 * </ul>
 *
 * A declined card is a {@code FAILED} outcome, not an exception. A refusal we recognise
 * throws {@link TransactionClosedException}, {@link HoldCancelledException} or
 * {@link HoldExpiredException}; any other one {@link PaymentProviderException}; no answer
 * at all {@link ProviderTimeoutException}.
 */
public interface PaymentProvider {

    /** Starts binding a card. The number and expiry are passed through, never kept. */
    CardBindingStart bindCardInit(String cardNumber, String expiry);

    BoundCard bindCardConfirm(String bindingId, String otp);

    void removeCard(String cardId, String cardToken);

    PendingCharge charge(ChargeRequest request);

    /** @param otp the cardholder's SMS code; null for a token charge */
    ChargeOutcome confirmCharge(List<String> transactionIds, String otp);

    ChargeOutcome getCharge(String transactionId);

    /** Gives one confirmed transaction back in full. */
    void reverse(String successTransId, String reason);

    HoldOutcome hold(HoldRequest request);

    /** @param otp null for a token hold */
    HoldOutcome confirmHold(String holdId, String otp);

    /** Takes the whole held amount. */
    HoldOutcome captureHold(String holdId);

    HoldOutcome releaseHold(String holdId);

    HoldOutcome getHold(String holdId);
}
