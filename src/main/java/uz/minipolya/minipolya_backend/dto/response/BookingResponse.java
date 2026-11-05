package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import uz.minipolya.minipolya_backend.provider.DeclineReason;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import uz.minipolya.minipolya_backend.entity.Booking;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A booking as returned to clients. {@code cancellationPolicy} lets players see
 * the terms before confirming; {@code refundAmount} is populated only on the
 * response to a cancellation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BookingResponse {

    private UUID id;
    private UUID stadiumId;
    private String stadiumName;
    private UUID userId;
    private UUID gameId;
    private UUID seriesId;
    private String source;
    private String status;
    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;
    private Integer durationHours;

    /**
     * Tiyin. {@code price} is what the player pays for the whole booking — the player price
     * D, the sum of its slots' player prices (the owner's own price on a manual booking).
     * {@code paidAmount} is what has been paid of it and {@code remainingAmount} what is
     * left: the same amount whether the player pays it in the app or the owner takes it in
     * cash.
     */
    private Long price;
    private Long paidAmount;
    private Long remainingAmount;
    private Booking.PaymentStatus paymentStatus;

    /** Owner and admin views only: the owner's own price for the booking. */
    private Long ownerPrice;
    /** Owner and admin views only: card money paid in the app and kept (refunds excluded). */
    private Long paidInApp;
    /**
     * Owner and admin views only: what the owner receives — the owner's share of the card
     * money kept, after ATMOS's fee, plus the cash taken.
     */
    private Long ownerReceives;
    /** Admin view only: what ATMOS takes of the card money kept. */
    private Long atmosFee;

    private String clientName;
    private String clientPhone;

    @JsonProperty("isWalkin")
    private boolean isWalkin;

    private OffsetDateTime expiresAt;
    private OffsetDateTime cancelledAt;
    private String cancellationReason;

    private CancellationPolicyResponse cancellationPolicy;

    /** The last moment a cancellation still earns a refund. */
    private OffsetDateTime cancellationDeadline;

    /**
     * Tiyin that would come back if the player cancelled right now — the sum of
     * this booking's card payments inside the window, 0 past it. Computed by the
     * same code that performs the cancellation, so it is what they will get.
     */
    private Long refundIfCancelledNow;

    /** Only set on a cancellation response: the refund actually issued, in tiyin. */
    private Long refundAmount;
    /**
     * R44: the latest card payment of this booking was refused, and why:
     * {@code INSUFFICIENT_FUNDS}, {@code CARD_EXPIRED} or {@code CARD_DECLINED}. Absent
     * otherwise — including once a later payment went through.
     */
    private DeclineReason declineReason;

    private OffsetDateTime createdAt;
}
