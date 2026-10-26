package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Paying off what is still owed on a partially paid booking. The amount is not
 * a choice — it is always the whole remainder.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayRemainderRequest {

    /** The saved card to charge. Must belong to the booking player and be active. */
    @NotNull
    private UUID cardId;
}
