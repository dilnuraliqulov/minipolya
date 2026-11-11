package uz.minipolya.minipolya_backend.util;

import uz.minipolya.minipolya_backend.dto.request.CreateStadiumRequest;
import uz.minipolya.minipolya_backend.dto.request.UpdateStadiumRequest;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.exception.BadRequestException;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.regex.Pattern;

/**
 * The rules for a stadium an owner adds or edits, in one place so create and update agree
 * (design D1, D10–D15). Each check throws a 400 with the message the API documents; text
 * fields are trimmed in the request as a side effect. On create every required field must
 * be there; on update only what is sent is checked — except the hours, where the pair that
 * would result (the sent value, or the current one) must still be valid.
 */
public final class StadiumRules {

    public static final int NAME_MIN = 3;
    public static final int NAME_MAX = 150;
    public static final int ADDRESS_MIN = 5;
    public static final int ADDRESS_MAX = 300;
    public static final int DESCRIPTION_MAX = 500;
    public static final int LANDMARK_MAX = 150;
    /** Tiyin: 10 000 so'm. */
    public static final long PRICE_MIN = 1_000_000L;
    /** Tiyin: 5 000 000 so'm. */
    public static final long PRICE_MAX = 500_000_000L;

    /** Uzbekistan's bounding box. */
    private static final BigDecimal LAT_MIN = new BigDecimal("37.1");
    private static final BigDecimal LAT_MAX = new BigDecimal("45.6");
    private static final BigDecimal LON_MIN = new BigDecimal("55.9");
    private static final BigDecimal LON_MAX = new BigDecimal("73.2");

    /** The same rule as the user profile's phone. */
    private static final Pattern PHONE = Pattern.compile("^\\+998\\d{9}$");

    private StadiumRules() {
    }

    public static void forCreate(CreateStadiumRequest r) {
        r.setName(text(required(r.getName(), "name"), "name", NAME_MIN, NAME_MAX));
        r.setAddress(text(required(r.getAddress(), "address"), "address", ADDRESS_MIN, ADDRESS_MAX));
        if (r.getDistrict() == null || r.getDistrict().isBlank()) {
            throw new BadRequestException("district is required");
        }
        r.setDistrict(Districts.require(r.getDistrict()));
        if (r.getPhone() == null || r.getPhone().isBlank()) {
            throw new BadRequestException("phone is required");
        }
        r.setPhone(phone(r.getPhone()));
        description(r.getDescription());
        r.setLandmark(landmark(r.getLandmark()));
        if (r.getLatitude() == null || r.getLongitude() == null) {
            throw new BadRequestException("latitude and longitude are required");
        }
        location(r.getLatitude(), r.getLongitude());
        if (r.getPricePerHour() == null) {
            throw new BadRequestException("pricePerHour is required");
        }
        price(r.getPricePerHour());
        if (r.getOpenTime() == null) {
            throw new BadRequestException("openTime is required");
        }
        if (r.getCloseTime() == null) {
            throw new BadRequestException("closeTime is required");
        }
        hours(r.getOpenTime(), r.getCloseTime());
    }

    /**
     * Only the fields sent are checked. A blank {@code landmark} stays non-null (empty) so
     * the caller knows to clear it; {@link #landmark} turns it into null.
     */
    public static void forUpdate(UpdateStadiumRequest r, Stadium current) {
        if (r.getName() != null) {
            r.setName(text(r.getName(), "name", NAME_MIN, NAME_MAX));
        }
        if (r.getAddress() != null) {
            r.setAddress(text(r.getAddress(), "address", ADDRESS_MIN, ADDRESS_MAX));
        }
        // Absent leaves the district as it is.
        r.setDistrict(Districts.require(r.getDistrict()));
        if (r.getPhone() != null) {
            r.setPhone(phone(r.getPhone()));
        }
        description(r.getDescription());
        if (r.getLandmark() != null) {
            String landmark = landmark(r.getLandmark());
            r.setLandmark(landmark == null ? "" : landmark);
        }
        if ((r.getLatitude() == null) != (r.getLongitude() == null)) {
            throw new BadRequestException("Send both latitude and longitude");
        }
        if (r.getLatitude() != null) {
            location(r.getLatitude(), r.getLongitude());
        }
        if (r.getPricePerHour() != null) {
            price(r.getPricePerHour());
        }
        if (r.getOpenTime() != null || r.getCloseTime() != null) {
            LocalTime open = r.getOpenTime() != null ? r.getOpenTime() : current.getOpenTime();
            LocalTime close = r.getCloseTime() != null ? r.getCloseTime() : current.getCloseTime();
            if (open != null && close != null) {
                hours(open, close);
            }
        }
    }

    /** Trimmed; blank is none (null). At most 150 characters. */
    public static String landmark(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > LANDMARK_MAX) {
            throw new BadRequestException("landmark must be at most " + LANDMARK_MAX + " characters");
        }
        return trimmed;
    }

    // ---- The individual rules ----

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " is required");
        }
        return value;
    }

    private static String text(String value, String field, int min, int max) {
        String trimmed = value.trim();
        if (trimmed.length() < min || trimmed.length() > max) {
            throw new BadRequestException(field + " must be between " + min + " and " + max + " characters");
        }
        return trimmed;
    }

    private static String phone(String value) {
        String trimmed = value.trim();
        if (!PHONE.matcher(trimmed).matches()) {
            throw new BadRequestException("phone must be in the format +998XXXXXXXXX");
        }
        return trimmed;
    }

    private static void description(String value) {
        if (value != null && value.length() > DESCRIPTION_MAX) {
            throw new BadRequestException("description must be at most " + DESCRIPTION_MAX + " characters");
        }
    }

    private static void location(BigDecimal lat, BigDecimal lon) {
        if (lat.compareTo(LAT_MIN) < 0 || lat.compareTo(LAT_MAX) > 0
                || lon.compareTo(LON_MIN) < 0 || lon.compareTo(LON_MAX) > 0) {
            throw new BadRequestException("Location must be inside Uzbekistan");
        }
    }

    private static void price(long pricePerHour) {
        if (pricePerHour < PRICE_MIN || pricePerHour > PRICE_MAX) {
            throw new BadRequestException("pricePerHour must be between 10 000 and 5 000 000 so'm");
        }
    }

    /**
     * Bookings start on the hour and slots are cut from {@code openTime}, and availability
     * walks open → close within one day: so both on the hour, and close after open (D14, D15).
     */
    private static void hours(LocalTime open, LocalTime close) {
        if (!onTheHour(open) || !onTheHour(close)) {
            throw new BadRequestException("openTime and closeTime must be on the hour");
        }
        if (!close.isAfter(open)) {
            throw new BadRequestException("closeTime must be after openTime; closing after midnight is not supported");
        }
    }

    private static boolean onTheHour(LocalTime time) {
        return time.getMinute() == 0 && time.getSecond() == 0 && time.getNano() == 0;
    }
}
