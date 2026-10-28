package uz.minipolya.minipolya_backend.provider;

/**
 * The provider did not answer in time. The operation may or may not have happened: the
 * payment stays {@code processing} and is settled by asking the provider later — never
 * assumed failed, never sent again.
 */
public class ProviderTimeoutException extends RuntimeException {

    public ProviderTimeoutException(String operation, Throwable cause) {
        super("Payment provider did not answer: " + operation, cause);
    }
}
