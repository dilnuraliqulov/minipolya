package uz.minipolya.minipolya_backend.exception;

import org.springframework.http.HttpStatus;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.provider.DeclineReason;

/**
 * R44: a card payment the provider refused, answered 400 with why as the {@code code}:
 * {@code INSUFFICIENT_FUNDS}, {@code CARD_EXPIRED} or {@code CARD_DECLINED} (the
 * fallback). Never the provider's own code or text.
 */
public class CardDeclinedException extends CodedApiException {

    public CardDeclinedException(DeclineReason reason, String message) {
        super(HttpStatus.BAD_REQUEST, (reason == null ? DeclineReason.CARD_DECLINED : reason).name(), message, null);
    }

    /** For a payment row that came back failed: its reason, or a plain decline. */
    public static CardDeclinedException of(Payment failed, String message) {
        return new CardDeclinedException(failed == null ? null : failed.getDeclineReason(), message);
    }
}
