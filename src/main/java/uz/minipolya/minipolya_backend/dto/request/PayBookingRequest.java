package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * R28: paying a pending booking again — after a declined card, with the same or another
 * card. The amount is not a choice: it is what the booking asked for when it was created
 * (the deposit or the whole price).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayBookingRequest {

    /** The saved card to charge. Must belong to the booking player and be active. */
    @NotNull
    private UUID cardId;
}
