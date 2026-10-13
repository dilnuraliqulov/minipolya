package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateBookingRequest {

    @NotNull
    private Long stadiumId;

    @NotNull
    @Future
    private OffsetDateTime startsAt;

    @NotNull
    @Min(value = 1, message = "durationHours must be 1 or 2")
    @Max(value = 2, message = "durationHours must be 1 or 2")
    private Integer durationHours;
}
