package uz.minipolya.minipolya_backend.provider;

/**
 * The provider refused an operation with a code we have no specific handling for. The
 * code is logged and kept for support; it is never shown raw to a player.
 */
public class PaymentProviderException extends RuntimeException {

    private final String code;

    public PaymentProviderException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
