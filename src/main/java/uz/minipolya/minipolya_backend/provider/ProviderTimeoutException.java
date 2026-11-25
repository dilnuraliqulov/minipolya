package uz.minipolya.minipolya_backend.provider;

/**
 * The provider did not answer in time. The operation may or may not have happened: the
 * payment stays {@code processing} and is settled by asking the provider later — never
 * assumed failed, never sent again.
 */
public class ProviderTimeoutException extends RuntimeException {

    public ProviderTimeoutException(String operation, Throwable cause) {
        this(operation, cause, "did not answer");
    }

    /**
     * The same "we do not know" with a different cause — an answer that could not be read,
     * for instance. What matters is that it is never read as a failure.
     *
     * @param what what happened, e.g. {@code "sent an answer we could not read"}
     */
    public ProviderTimeoutException(String operation, Throwable cause, String what) {
        super("Payment provider " + what + ": " + operation, cause);
    }
}
