package uz.minipolya.minipolya_backend.exception;

import org.springframework.http.HttpStatus;

/**
 * R28: a pending booking cannot be paid now. HTTP 409 with a {@code code}:
 * {@code BOOKING_NOT_PENDING}, {@code BOOKING_EXPIRED} or {@code PAYMENT_IN_PROGRESS}.
 */
public class BookingPaymentConflictException extends CodedApiException {

    public static final String NOT_PENDING = "BOOKING_NOT_PENDING";
    public static final String EXPIRED = "BOOKING_EXPIRED";
    public static final String IN_PROGRESS = "PAYMENT_IN_PROGRESS";

    public BookingPaymentConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message, null);
    }
}
