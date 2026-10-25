package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
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
public class CreateBookingRequest {

    @NotNull
    private UUID stadiumId;

    @NotNull
    @Future
    private OffsetDateTime startsAt;

    @NotNull
    @Min(value = 1, message = "durationHours must be at least 1")
    private Integer durationHours;

    /** The saved card to charge. Must belong to the booking player and be active. */
    @NotNull
    private UUID cardId;

    /** MINIMUM (the stadium's minimum) or FULL (the whole price). */
    @NotNull
    private PaymentOption paymentOption;
}
