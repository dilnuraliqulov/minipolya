package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.minipolya.minipolya_backend.entity.Payment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A payment or a refund for the admin panel. Money is tiyin and negative on a refund;
 * {@code amount = ownerAmount + platformAmount}. {@code cardFee} is what the card fee and
 * the price rounding added to the net ({@code card_fee_expected}); {@code providerCommission}
 * is what ATMOS reports having taken, absent until it does. Absent fields are omitted.
 * {@code linkedPayments} is only on the detail.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminPaymentResponse {

    private UUID id;
    private OffsetDateTime createdAt;

    /** The payer. */
    private UUID userId;
    private String userName;
    private String userPhone;

    private Payment.PaymentType paymentType;
    private UUID stadiumId;
    private String stadiumName;
    private UUID bookingId;
    private UUID gameId;

    private Long amount;
    private Long ownerAmount;
    private Long platformAmount;
    private Long cardFee;
    private Long providerCommission;
    private Integer commissionPercent;

    private Payment.Method method;
    private Payment.Provider provider;
    private String providerTxnId;
    private Payment.Status status;

    private String refundReason;
    private UUID refundOfPaymentId;

    /** A refund the provider declined: its error, for the admin. */
    private String failureReason;
    private Boolean refundedManually;
    private String manualRefundReason;

    private List<LinkedPayment> linkedPayments;

    /** A refund's original, or one of an original's refunds. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LinkedPayment {
        private UUID id;
        private Payment.PaymentType paymentType;
        private Long amount;
        private Payment.Status status;
        private Payment.Method method;
        private String providerTxnId;
        private OffsetDateTime createdAt;
    }
}
