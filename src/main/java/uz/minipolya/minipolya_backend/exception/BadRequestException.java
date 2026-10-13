package uz.minipolya.minipolya_backend.exception;

/**
 * Business-rule validation failure that maps to HTTP 400 (e.g. an invalid
 * time range or a pricing rule that overlaps an existing one).
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
