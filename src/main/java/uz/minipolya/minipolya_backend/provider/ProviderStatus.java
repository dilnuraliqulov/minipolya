package uz.minipolya.minipolya_backend.provider;

/**
 * Where a provider charge or hold stands. {@code PENDING} is not final: the operation is
 * still in flight (or its answer was lost) and must be asked again, never assumed.
 */
public enum ProviderStatus {
    PENDING,
    SUCCESS,
    FAILED,
    REVERSED,
    HELD,
    CAPTURED,
    RELEASED;

    public boolean isFinal() {
        return this != PENDING;
    }
}
