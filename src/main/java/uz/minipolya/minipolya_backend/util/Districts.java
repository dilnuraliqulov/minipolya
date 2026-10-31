package uz.minipolya.minipolya_backend.util;

import uz.minipolya.minipolya_backend.exception.BadRequestException;

import java.util.List;

/**
 * The twelve Tashkent districts a stadium can be in — the same strings as the
 * {@code chk_stadiums_district} constraint. "Mirzo Ulugʻbek" is spelt with U+02BB
 * (modifier letter turned comma), not an apostrophe.
 */
public final class Districts {

    public static final List<String> ALL = List.of(
            "Bektemir", "Chilonzor", "Mirobod", "Mirzo Ulugʻbek", "Olmazor", "Sergeli",
            "Shayxontohur", "Uchtepa", "Yakkasaroy", "Yangihayot", "Yashnobod", "Yunusobod");

    private Districts() {
    }

    /** The value itself when it is a district, null when absent; 400 {@code "Invalid district"} otherwise. */
    public static String require(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!ALL.contains(value)) {
            throw new BadRequestException("Invalid district");
        }
        return value;
    }
}
