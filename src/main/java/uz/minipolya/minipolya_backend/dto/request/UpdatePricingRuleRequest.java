package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

/**
 * Partial update for a pricing rule. All fields optional — only non-null values
 * are applied. Activation state is managed via the delete (soft-delete) endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePricingRuleRequest {

    @Min(value = 0, message = "dayOfWeek must be 0 (Monday) to 6 (Sunday)")
    @Max(value = 6, message = "dayOfWeek must be 0 (Monday) to 6 (Sunday)")
    private Integer dayOfWeek;

    private LocalTime startTime;

    private LocalTime endTime;

    @Positive
    private Long pricePerHour;
}
