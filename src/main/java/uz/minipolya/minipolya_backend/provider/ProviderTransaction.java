package uz.minipolya.minipolya_backend.provider;

/**
 * The provider's answer to a charge, hold, capture, release or refund.
 *
 * @param providerTxnId the provider's transaction id; unique, and the handle for
 *                      capture, release and refund
 * @param status        where the transaction stands
 */
public record ProviderTransaction(String providerTxnId, ProviderTransactionStatus status) {

    public boolean failed() {
        return status == ProviderTransactionStatus.FAILED;
    }
}
