package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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

/**
 * Partial update for a stadium. All fields optional — only non-null values are
 * applied. {@code status} is intentionally absent (managed by the admin
 * approval flow, not by owners).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateStadiumRequest {

    @Size(max = 150)
    private String name;

    @Size(max = 300)
    private String address;

    @DecimalMin(value = "-90.0")
    @DecimalMax(value = "90.0")
    private BigDecimal latitude;

    @DecimalMin(value = "-180.0")
    @DecimalMax(value = "180.0")
    private BigDecimal longitude;

    @Size(max = 20)
    private String phone;

    private String description;

    @Pattern(regexp = "5x5|7x7|9x9|11x11", message = "format must be one of: 5x5, 7x7, 9x9, 11x11")
    private String format;

    @Pattern(regexp = "natural|artificial|hybrid", message = "surfaceType must be one of: natural, artificial, hybrid")
    private String surfaceType;

    private LocalTime openTime;

    private LocalTime closeTime;

    @PositiveOrZero
    private Integer pricePerHour;

    @PositiveOrZero
    private Integer fullRefundHours;

    @PositiveOrZero
    private Integer partialRefundHours;

    @PositiveOrZero
    private Integer partialRefundPercent;

    private Boolean isActive;

    private List<@jakarta.validation.constraints.NotBlank @Size(max = 500) String> photos;

    @Valid
    private AmenitiesRequest amenities;
}
