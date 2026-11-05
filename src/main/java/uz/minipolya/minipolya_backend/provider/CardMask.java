package uz.minipolya.minipolya_backend.provider;

/**
 * The one display form of a card number, whichever provider bound it:
 * {@code 8600 •••• •••• 1234} — the first four and last four digits only.
 */
public final class CardMask {

    private static final String HIDDEN = " •••• •••• ";

    private CardMask() {
    }

    /** From a full or provider-masked number ({@code 986009******1840}); digits and {@code *} are read. */
    public static String pan(String pan) {
        String clean = pan == null ? "" : pan.replaceAll("[^0-9*]", "");
        if (clean.length() < 8) {
            return "••••" + HIDDEN + (clean.length() >= 4 ? clean.substring(clean.length() - 4) : "••••");
        }
        return clean.substring(0, 4) + HIDDEN + clean.substring(clean.length() - 4);
    }

    /** {@code +998901234558} → {@code +998 ** *** ** 58}; null or too short → {@code +998 ** *** ** **}. */
    public static String phone(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        String last = digits.length() >= 2 ? digits.substring(digits.length() - 2) : "**";
        return "+998 ** *** ** " + last;
    }

    /** {@code uzcard} (8600…), {@code humo} (9860…) or {@code unknown}, from the first digits. */
    public static String type(String pan) {
        String digits = pan == null ? "" : pan.replaceAll("\\D", "");
        if (digits.startsWith("8600")) {
            return "uzcard";
        }
        if (digits.startsWith("9860")) {
            return "humo";
        }
        return "unknown";
    }
}
