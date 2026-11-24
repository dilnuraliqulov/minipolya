package uz.minipolya.minipolya_backend.provider.atmos;

import java.util.Locale;

/**
 * Which field of a split names the recipient it is paid to: {@code store_id} or
 * {@code terminal_id}.
 *
 * <p>It is a setting because the ATMOS docs do not agree with themselves. The bulk-payment
 * example sends one {@code store_id} at the top and items with no recipient at all, while
 * the parameter table lists both {@code store_id} and {@code terminal_id} as item fields —
 * and every response names a {@code terminal_id} beside the store. Until ATMOS says which
 * one identifies the account the money lands in, it can be changed without a release:
 * {@code ATMOS_RECIPIENT_FIELD=terminal_id} and a restart.
 *
 * <p>{@link #STORE_ID} is the default because that is what the request examples show.
 * With {@link #TERMINAL_ID} the recipient goes in {@code terminal_id} and {@code store_id}
 * stays our own merchant store, which is how the single-payment example reads.
 */
public enum AtmosRecipientField {

    STORE_ID,
    TERMINAL_ID;

    /** The environment variable this comes from. */
    public static final String VARIABLE = "ATMOS_RECIPIENT_FIELD";

    /** The field name as it goes into the JSON body. */
    public String field() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The configured field, {@link #STORE_ID} when it is not set. Spaces, capitals and a
     * dash instead of an underscore are forgiven.
     *
     * @throws IllegalStateException when it is neither
     */
    public static AtmosRecipientField of(String configured) {
        if (configured == null || configured.isBlank()) {
            return STORE_ID;
        }
        String value = configured.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (AtmosRecipientField candidate : values()) {
            if (candidate.field().equals(value)) {
                return candidate;
            }
        }
        throw new IllegalStateException(VARIABLE + " must be exactly \"store_id\" or \"terminal_id\" "
                + "(it is neither, and is not repeated here in case the wrong variable was pasted into it). "
                + "Leave it empty to mean store_id.");
    }
}
