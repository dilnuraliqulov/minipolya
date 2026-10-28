package uz.minipolya.minipolya_backend.audit;

/** The {@code entity_type} values the audit log uses — one per kind of thing acted on. */
public final class AuditEntityType {

    public static final String USER = "user";
    public static final String STADIUM = "stadium";
    public static final String OWNER_APPLICATION = "owner_application";
    public static final String PLATFORM_SETTINGS = "platform_settings";
    public static final String PRICING_RULE = "pricing_rule";
    public static final String CLOSURE = "stadium_closure";
    public static final String PROMOTION = "promotion";
    public static final String BOOKING = "booking";
    public static final String BOOKING_SERIES = "booking_series";
    public static final String GAME = "game";
    public static final String PAYMENT = "payment";

    private AuditEntityType() {
    }
}
