package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateBookingSeriesRequest {

    @NotNull
    private UUID stadiumId;

    @NotNull
    @Min(value = 0, message = "dayOfWeek must be 0 (Monday) to 6 (Sunday)")
    @Max(value = 6, message = "dayOfWeek must be 0 (Monday) to 6 (Sunday)")
    private Integer dayOfWeek;

    @NotNull
    private LocalTime startTime;

    /** Optional: each booking of the series is exactly one hour. Absent or 1; anything else is 400. */
    private Integer duration;
}
