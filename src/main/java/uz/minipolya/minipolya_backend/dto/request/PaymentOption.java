package uz.minipolya.minipolya_backend.dto.request;

/**
 * How much of a booking the player pays up front. These are the only two
 * choices — no arbitrary amounts.
 */
public enum PaymentOption {

    /**
     * The stadium's {@code min_booking_amount}. Allowed only when the stadium
     * has one and it is below this booking's price.
     */
    MINIMUM,

    /** The whole booking price. */
    FULL
}
