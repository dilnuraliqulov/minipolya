package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import uz.minipolya.minipolya_backend.provider.DeclineReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.minipolya.minipolya_backend.entity.Payment;

import java.time.OffsetDateTime;
import java.util.UUID;

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

    private UUID id;
    private UUID bookingId;
    private UUID gameId;
    private UUID userId;
    /** Tiyin: what the player paid (negative on a refund). */
    private Long amount;

    private Payment.Method method;
    private Payment.PaymentType paymentType;
    private Payment.Provider provider;
    private String providerTxnId;
    private Payment.Status status;
    private String refundReason;
    /** R44: on a failed row the provider refused, why (INSUFFICIENT_FUNDS, CARD_EXPIRED, CARD_DECLINED). */
    private DeclineReason declineReason;
    private OffsetDateTime createdAt;
}
