package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateStadiumRequest {

    @NotBlank
    @Size(max = 150)
    private String name;

    @NotBlank
    @Size(max = 300)
    private String address;

    /** Optional: one of the twelve Tashkent districts, spelt exactly as listed in {@code Districts}. */
    @Size(max = 30)
    private String district;

    @DecimalMin(value = "-90.0")
    @DecimalMax(value = "90.0")
    private BigDecimal latitude;

    @DecimalMin(value = "-180.0")
    @DecimalMax(value = "180.0")
    private BigDecimal longitude;

    @Size(max = 20)
    private String phone;

    private String description;

    @NotBlank
    @Pattern(regexp = "5x5|7x7|9x9|11x11", message = "format must be one of: 5x5, 7x7, 9x9, 11x11")
    private String format;

    @NotBlank
    @Pattern(regexp = "natural|artificial|hybrid", message = "surfaceType must be one of: natural, artificial, hybrid")
    private String surfaceType;

    @NotNull
    private LocalTime openTime;

    @NotNull
    private LocalTime closeTime;

    @NotNull
    @PositiveOrZero
    private Long pricePerHour;

    /** Tiyin; null means the player must pay in full. At least 50 000 (500 so.m). */
    @PositiveOrZero
    private Long minBookingAmount;

    /** Hours before kick-off up to which a player can cancel and be refunded in full. */
    @PositiveOrZero
    private Integer cancellationHours;

    private List<@NotBlank @Size(max = 500) String> photos;

    @Valid
    private AmenitiesRequest amenities;
}
