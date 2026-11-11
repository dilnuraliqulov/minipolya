package uz.minipolya.minipolya_backend.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phone numbers for logs and for showing a user their own number back: {@code +998901234558}
 * becomes {@code +998 90 *** ** 58}. Anything else keeps only its last two digits.
 */
public final class PhoneMask {

    private static final Pattern UZ = Pattern.compile("^\\+998(\\d{2})\\d{5}(\\d{2})$");

    private PhoneMask() {
    }

    public static String mask(String phone) {
        if (phone == null || phone.isBlank()) {
            return "(none)";
        }
        Matcher uz = UZ.matcher(phone);
        if (uz.matches()) {
            return "+998 " + uz.group(1) + " *** ** " + uz.group(2);
        }
        String digits = phone.replaceAll("\\D", "");
        return digits.length() < 2 ? "***" : "*** " + digits.substring(digits.length() - 2);
    }
}
