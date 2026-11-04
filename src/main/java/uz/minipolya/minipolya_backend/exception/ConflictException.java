package uz.minipolya.minipolya_backend.exception;

/** The request clashes with the current state of the resource: HTTP 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
