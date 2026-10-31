package uz.minipolya.minipolya_backend.provider;

/** The hold lapsed before it was captured (ATMOS STPIMS-ERR-144; the fake's HOLD_EXPIRED). */
public class HoldExpiredException extends PaymentProviderException {

    public HoldExpiredException(String code) {
        super(code, "Hold has expired");
    }
}
