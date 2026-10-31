package uz.minipolya.minipolya_backend.provider;

/** The transaction is already closed — for example reversed before (ATMOS STPIMS-ERR-092). */
public class TransactionClosedException extends PaymentProviderException {

    public TransactionClosedException(String code) {
        super(code, "Transaction is closed");
    }
}
