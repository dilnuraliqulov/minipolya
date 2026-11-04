package uz.minipolya.minipolya_backend.exception;

/** An upload over the size limit: HTTP 413. */
public class PayloadTooLargeException extends RuntimeException {
    public PayloadTooLargeException(String message) {
        super(message);
    }
}
