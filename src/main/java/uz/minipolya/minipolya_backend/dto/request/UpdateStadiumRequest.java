package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * Partial update for a stadium. All fields optional — only non-null values are applied,
 * each checked by {@code StadiumRules} as on create. {@code status} is absent (the admin's
 * approval flow), and so are the booking terms ({@code minBookingAmount},
 * {@code cancellationHours}: the admin's {@code PUT /api/admin/stadiums/{id}/booking-terms})
 * and the photo URLs (the upload endpoint): a client still sending them is ignored.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateStadiumRequest {

    private String name;

    private String address;

    /** One of the twelve Tashkent districts, spelt exactly as listed in {@code Districts}. */
    private String district;

    /** At most 150 characters; trimmed. Blank clears it. */
    private String landmark;

    /** With {@code longitude}, both or neither. */
    private BigDecimal latitude;

    private BigDecimal longitude;

    private String phone;

    private String description;

    @Pattern(regexp = "5x5|7x7|9x9|11x11", message = "format must be one of: 5x5, 7x7, 9x9, 11x11")
    private String format;

    @Pattern(regexp = "natural|artificial|hybrid", message = "surfaceType must be one of: natural, artificial, hybrid")
    private String surfaceType;

    private LocalTime openTime;

    private LocalTime closeTime;

    private Long pricePerHour;

    private Boolean isActive;

    @Valid
    private AmenitiesRequest amenities;
}
