package uz.minipolya.minipolya_backend.repository;

import uz.minipolya.minipolya_backend.entity.Payment;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The columns the revenue figures need from one card payment, and from the payment a
 * refund gives back ({@code original*}, null on anything but a refund).
 */
public record RevenueRow(UUID id,
                         UUID stadiumId,
                         Payment.PaymentType paymentType,
                         Payment.Provider provider,
                         long amount,
                         long ownerAmount,
                         long platformAmount,
                         Long providerCommission,
                         OffsetDateTime createdAt,
                         OffsetDateTime updatedAt,
                         Payment.Provider originalProvider,
                         Long originalAmount,
                         Long originalProviderCommission,
                         boolean refundedManually) {

    public boolean isRefund() {
        return paymentType == Payment.PaymentType.REFUND;
    }

    /** When the row counts: a payment when it was taken, a refund when it completed. */
    public OffsetDateTime countedAt() {
        return isRefund() ? updatedAt : createdAt;
    }
}
