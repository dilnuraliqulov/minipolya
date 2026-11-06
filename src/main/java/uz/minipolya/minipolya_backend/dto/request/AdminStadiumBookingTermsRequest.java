package uz.minipolya.minipolya_backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A stadium's booking terms, set by an admin (D3) — the whole terms each time: an absent or
 * null {@code minBookingAmount} means full payment. Checked by the service so the messages
 * read exactly as documented.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminStadiumBookingTermsRequest {

    /** Tiyin; null = the player pays in full. Else at least 50 000 and at most pricePerHour. */
    private Long minBookingAmount;

    /** Required, 0–168: hours before kick-off up to which a cancellation is refunded in full. */
    private Integer cancellationHours;
}
