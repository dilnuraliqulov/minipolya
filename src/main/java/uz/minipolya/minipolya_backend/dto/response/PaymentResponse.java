package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.minipolya.minipolya_backend.entity.Payment;

import java.time.OffsetDateTime;

/**
 * A payment as returned to clients. The enum fields serialize to their lowercase
 * value (via {@code @JsonValue} on the enums).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentResponse {

    private Long id;
    private Long bookingId;
    private Long gameId;
    private Long userId;
    private Integer amount;
    private Payment.PaymentType paymentType;
    private Payment.Provider provider;
    private String providerTxnId;
    private Payment.Status status;
    private String refundReason;
    private OffsetDateTime createdAt;
}
