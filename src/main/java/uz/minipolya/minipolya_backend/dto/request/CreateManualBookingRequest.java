package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateManualBookingRequest {

    @NotNull
    private UUID stadiumId;

    @NotBlank
    private String clientName;

    @NotBlank
    private String clientPhone;

    @NotNull
    private OffsetDateTime startsAt;

    @NotNull
    @Min(value = 1, message = "duration must be at least 1 hour")
    private Integer duration;

    private String notes;

    /** "cash" or null — defaults to cash for walk-ins. */
    private String paymentMethod;
}
