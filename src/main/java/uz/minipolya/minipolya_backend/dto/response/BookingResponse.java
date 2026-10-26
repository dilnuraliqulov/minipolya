package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    /** All three in tiyin: the total, what has been paid, and what is still owed. */
    private Long price;
    private Long paidAmount;
    private Long remainingAmount;
    private Booking.PaymentStatus paymentStatus;

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

    private OffsetDateTime createdAt;
}
