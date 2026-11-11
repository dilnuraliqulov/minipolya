package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * A new stadium. The field rules (lengths, district, phone, location, price, hours) are
 * checked by {@code StadiumRules}, the same code the update uses, so the messages match.
 * Booking terms ({@code minBookingAmount}, {@code cancellationHours}) are the admin's
 * (D2/D3) and photos come only through the upload endpoint (D8): a client still sending
 * those fields is ignored, not refused.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateStadiumRequest {

    /** Required, trimmed, 3–150 characters. */
    private String name;

    /** Required, trimmed, 5–300 characters. */
    private String address;

    /** Required: one of the twelve Tashkent districts, spelt exactly as listed in {@code Districts}. */
    private String district;

    /** Optional, at most 150 characters; trimmed, blank means none. */
    private String landmark;

    /** Required, inside Uzbekistan (lat 37.1–45.6). */
    private BigDecimal latitude;

    /** Required, inside Uzbekistan (lon 55.9–73.2). */
    private BigDecimal longitude;

    /** Required, {@code +998XXXXXXXXX}. */
    private String phone;

    /** Optional, at most 500 characters. */
    private String description;

    @NotBlank
    @Pattern(regexp = "5x5|7x7|9x9|11x11", message = "format must be one of: 5x5, 7x7, 9x9, 11x11")
    private String format;

    @NotBlank
    @Pattern(regexp = "natural|artificial|hybrid", message = "surfaceType must be one of: natural, artificial, hybrid")
    private String surfaceType;

    /** Required, on the hour, before {@code closeTime}. */
    private LocalTime openTime;

    /** Required, on the hour, after {@code openTime} (no closing after midnight). */
    private LocalTime closeTime;

    /** Required, tiyin, 1 000 000–500 000 000 (10 000–5 000 000 so'm): the owner's own price. */
    private Long pricePerHour;

    @Valid
    private AmenitiesRequest amenities;
}
