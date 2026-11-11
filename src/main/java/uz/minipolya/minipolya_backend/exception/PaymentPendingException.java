package uz.minipolya.minipolya_backend.exception;

import org.springframework.http.HttpStatus;

/**
 * A payment step got no clear answer: nothing failed, nothing is finished. HTTP 202 with
 * code {@code PAYMENT_PENDING}; the reconcile job settles it and the player is notified of
 * the outcome. Every step up to here is already committed.
 */
public class PaymentPendingException extends CodedApiException {

    public PaymentPendingException(String message) {
        super(HttpStatus.ACCEPTED, "PAYMENT_PENDING", message, null);
    }
}
