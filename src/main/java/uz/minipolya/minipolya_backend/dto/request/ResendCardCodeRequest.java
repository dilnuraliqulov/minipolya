package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Asks for a card binding's SMS code again. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResendCardCodeRequest {

    @NotBlank
    private String bindingId;
}
