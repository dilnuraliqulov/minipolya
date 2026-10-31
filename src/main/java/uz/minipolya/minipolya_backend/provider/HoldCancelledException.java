package uz.minipolya.minipolya_backend.provider;

/** The hold was already cancelled or released (ATMOS STPIMS-ERR-145) and cannot be settled again. */
public class HoldCancelledException extends PaymentProviderException {

    public HoldCancelledException(String code) {
        super(code, "Hold is cancelled");
    }
}
