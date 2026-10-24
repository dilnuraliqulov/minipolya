package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Confirms a card binding with the SMS code the provider sent the cardholder. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmCardRequest {

    @NotBlank
    private String bindingId;

    @NotBlank
    private String smsCode;
}
