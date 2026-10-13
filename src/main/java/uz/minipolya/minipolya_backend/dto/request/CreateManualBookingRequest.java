package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
public class CreateManualBookingRequest {

    @NotNull
    private Long stadiumId;

    @NotBlank
    private String clientName;

    @NotBlank
    private String clientPhone;

    @NotNull
    private OffsetDateTime startsAt;

    @NotNull
    @Min(value = 1, message = "duration must be 1 or 2 hours")
    @Max(value = 2, message = "duration must be 1 or 2 hours")
    private Integer duration;

    private String notes;

    /** "cash" or null — defaults to cash for walk-ins. */
    private String paymentMethod;
}
