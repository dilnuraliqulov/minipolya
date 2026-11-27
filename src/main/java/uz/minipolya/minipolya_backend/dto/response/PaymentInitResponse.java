package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** A casual game share charged to a saved card: the payment, its provider and where it stands. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentInitResponse {

    private UUID paymentId;
    private String provider;
    /** For a saved-card payment: paid, or processing while the provider has not answered. */
    private String status;
}
