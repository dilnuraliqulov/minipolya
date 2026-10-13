package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

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

    private Long id;
    private Long stadiumId;
    private String stadiumName;
    private Long userId;
    private Long gameId;
    private Long seriesId;
    private String source;
    private String status;
    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;
    private Integer durationHours;
    private Integer price;

    private String clientName;
    private String clientPhone;

    @JsonProperty("isWalkin")
    private boolean isWalkin;

    private OffsetDateTime expiresAt;
    private OffsetDateTime cancelledAt;
    private String cancellationReason;

    private CancellationPolicyResponse cancellationPolicy;

    /** Only set on a cancellation response: the refund due, in so'm. */
    private Integer refundAmount;

    private OffsetDateTime createdAt;
}
