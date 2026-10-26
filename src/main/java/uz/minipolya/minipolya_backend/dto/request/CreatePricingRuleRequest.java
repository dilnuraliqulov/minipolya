package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePricingRuleRequest {

    @NotNull
    @Min(value = 0, message = "dayOfWeek must be 0 (Monday) to 6 (Sunday)")
    @Max(value = 6, message = "dayOfWeek must be 0 (Monday) to 6 (Sunday)")
    private Integer dayOfWeek;

    @NotNull
    private LocalTime startTime;

    @NotNull
    private LocalTime endTime;

    @NotNull
    @Positive
    private Long pricePerHour;
}
