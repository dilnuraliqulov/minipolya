package uz.minipolya.minipolya_backend.provider;

import java.util.List;

/**
 * A charge the provider has created but not yet taken: one transaction per split.
 * {@link PaymentProvider#confirmCharge} takes it — with the SMS code when
 * {@code otpRequired}, which is the case for a card number rather than a token.
 */
public record PendingCharge(List<String> transactionIds, boolean otpRequired) {

    public PendingCharge {
        transactionIds = List.copyOf(transactionIds);
    }
}
