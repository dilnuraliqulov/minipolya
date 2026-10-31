package uz.minipolya.minipolya_backend.exception;

/**
 * A step of a multi-step payment failed after an earlier step had already moved money
 * (or may have). The steps taken so far — and their undoing — are committed, never rolled
 * back: the provider cannot be rolled back with the database. Methods that throw this
 * declare {@code noRollbackFor = PaymentStepFailedException.class}. HTTP 400, like any
 * other {@link BadRequestException}.
 */
public class PaymentStepFailedException extends BadRequestException {

    public PaymentStepFailedException(String message) {
        super(message);
    }
}
