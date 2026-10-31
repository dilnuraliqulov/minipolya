package uz.minipolya.minipolya_backend.exception;

/**
 * The payment provider could not be reached at all, so nothing is known to have
 * happened — maps to HTTP 502. The caller may simply try again.
 */
public class ProviderUnavailableException extends RuntimeException {
    public ProviderUnavailableException(String message) {
        super(message);
    }
}
