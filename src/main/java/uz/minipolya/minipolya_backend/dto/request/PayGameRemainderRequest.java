package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** A team leader settling their own team's remainder from a saved card. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayGameRemainderRequest {

    @NotNull
    private UUID cardId;
}
