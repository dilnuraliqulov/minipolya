package uz.minipolya.minipolya_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A started card binding. The client sends {@code bindingId} back with the SMS
 * code to finish it; nothing is stored until then.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardBindingResponse {

    private String bindingId;
    /** Where the SMS code went, masked: ********9999. */
    private String maskedPhone;
}
